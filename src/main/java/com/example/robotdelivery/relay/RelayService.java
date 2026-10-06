package com.example.robotdelivery.relay;

import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.cs.CaseType;
import com.example.robotdelivery.cs.CsService;
import com.example.robotdelivery.cs.Responsibility;
import com.example.robotdelivery.elevator.ElevatorRequestRepository;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import com.example.robotdelivery.robot.CargoCompartmentRepository;
import com.example.robotdelivery.robot.CompartmentStatus;
import com.example.robotdelivery.robot.Robot;
import com.example.robotdelivery.robot.RobotRepository;
import com.example.robotdelivery.robot.RobotStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 人工接力编排（电梯连续满载触发）：
 * 派单登记接力人员身份 -> 机器人在大堂为接力人员开仓 -> 接力人员接货（载物仓交接、释放机器人）
 * -> 接力送达用户签收；商品损坏/延迟按机器人等待、人工接力、用户取件三个阶段判定责任。
 */
@Service
public class RelayService {

    private final RelayHandoverRepository handoverRepository;
    private final DeliveryOrderRepository orderRepository;
    private final ElevatorRequestRepository elevatorRepository;
    private final RobotRepository robotRepository;
    private final CargoCompartmentRepository compartmentRepository;
    private final DeliveryEventService eventService;
    private final CsService csService;
    private final FeeCalculator feeCalculator;

    public RelayService(RelayHandoverRepository handoverRepository,
                        DeliveryOrderRepository orderRepository,
                        ElevatorRequestRepository elevatorRepository,
                        RobotRepository robotRepository,
                        CargoCompartmentRepository compartmentRepository,
                        DeliveryEventService eventService,
                        CsService csService,
                        FeeCalculator feeCalculator) {
        this.handoverRepository = handoverRepository;
        this.orderRepository = orderRepository;
        this.elevatorRepository = elevatorRepository;
        this.robotRepository = robotRepository;
        this.compartmentRepository = compartmentRepository;
        this.eventService = eventService;
        this.csService = csService;
        this.feeCalculator = feeCalculator;
    }

    /**
     * 接力派单：登记接力人员身份（姓名/手机号/公司/工号），重算配送费用，
     * 订单进入 RELAYING 状态；机器人仍在大堂载物等候，直到开仓交接完成。
     */
    @Transactional
    public RelayHandover dispatch(Long deliveryId, String courierName, String courierPhone,
                                  String courierCompany, String courierEmployeeNo) {
        DeliveryOrder order = mustGetOrder(deliveryId);
        if (order.getStatus() != DeliveryStatus.ELEVATOR_WAIT) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许接力派单（需在等待电梯且策略为人工接力）");
        }
        handoverRepository.findFirstByDeliveryIdOrderByIdDesc(deliveryId).ifPresent(h -> {
            throw ApiException.conflict("该配送单已存在接力交接单: " + h.getHandoverNo());
        });

        int floorDelta = latestFloorDelta(order.getId());
        FeeCalculator.FeeResult fee = feeCalculator.relayFee(order, floorDelta);

        RelayHandover handover = new RelayHandover();
        handover.setHandoverNo("H" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        handover.setDelivery(order);
        handover.setHandoverType(RelayType.UPSTAIRS_RELAY);
        handover.setStatus(RelayStatus.DISPATCHED);
        handover.setCourierName(courierName);
        handover.setCourierPhone(courierPhone);
        handover.setCourierCompany(courierCompany);
        handover.setCourierEmployeeNo(courierEmployeeNo);
        handover.setCompartmentNo(order.getCompartmentNo());
        handover.setDispatchedAt(LocalDateTime.now());
        handover.setFee(fee.total());
        handoverRepository.save(handover);

        order.setStatus(DeliveryStatus.RELAYING);
        order.setRelayCourier(courierName);
        order.setRelayStartedAt(handover.getDispatchedAt());
        order.setDeliveryFee(fee.total());
        order.setDeliveryFeeDetail(fee.detail());
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order, handover);
        payload.put("courierPhone", courierPhone);
        payload.put("courierCompany", courierCompany);
        payload.put("courierEmployeeNo", courierEmployeeNo);
        payload.put("floorDelta", floorDelta);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RELAY_DISPATCHED, EventActor.OPS, payload);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.MANUAL_RELAY, EventActor.OPS, payload);

        Map<String, Object> feePayload = basePayload(order, handover);
        feePayload.put("fee", fee.total());
        feePayload.put("feeDetail", fee.detail());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RELAY_FEE_RECALCULATED, EventActor.SYSTEM, feePayload);
        return handover;
    }

    /** 机器人在大堂为接力人员打开载物仓（仓门开启记录） */
    @Transactional
    public RelayHandover openCompartment(Long handoverId) {
        RelayHandover handover = mustGet(handoverId);
        if (handover.getStatus() != RelayStatus.DISPATCHED) {
            throw ApiException.conflict("交接单状态[" + handover.getStatus() + "]不允许开启载物仓");
        }
        handover.setStatus(RelayStatus.COMPARTMENT_OPENED);
        handover.setCompartmentOpenedAt(LocalDateTime.now());
        handoverRepository.save(handover);

        DeliveryOrder order = handover.getDelivery();
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RELAY_COMPARTMENT_OPENED, EventActor.ROBOT, basePayload(order, handover));
        return handover;
    }

    /**
     * 接力人员接货：载物仓交接完成，记录接货时间并释放机器人与载物仓。
     * 开仓时发现商品损坏（商品此前一直处于机器人载物仓内）按机器人等待阶段定责。
     */
    @Transactional
    public RelayHandover handover(Long handoverId, boolean damageReported, String damageNote) {
        RelayHandover handover = mustGet(handoverId);
        if (handover.getStatus() != RelayStatus.COMPARTMENT_OPENED) {
            throw ApiException.conflict("交接单状态[" + handover.getStatus() + "]不允许确认接货（需先开启载物仓）");
        }
        LocalDateTime now = LocalDateTime.now();
        handover.setStatus(RelayStatus.HANDED_OVER);
        handover.setHandedOverAt(now);
        handoverRepository.save(handover);

        DeliveryOrder order = handover.getDelivery();
        order.setHandoverAt(now);
        orderRepository.save(order);
        releaseRobotAndCompartment(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RELAY_HANDOVER, EventActor.OPS, basePayload(order, handover));

        if (damageReported) {
            handover.setDamageReported(true);
            handover.setDamageNote(damageNote);
            handoverRepository.save(handover);
            reportIssue(order, handover, IssueType.DAMAGE, DeliveryStage.ROBOT_WAITING,
                    damageNote == null ? "接力人员开仓接货时发现商品损坏" : damageNote);
        }
        return handover;
    }

    /** 接力人员送达，用户签收；可上报接力段商品损坏（费用减免）与自动判定延迟 */
    @Transactional
    public RelayHandover sign(Long handoverId, String signBy, boolean damageReported, String damageNote) {
        RelayHandover handover = mustGet(handoverId);
        if (handover.getStatus() != RelayStatus.HANDED_OVER) {
            throw ApiException.conflict("交接单状态[" + handover.getStatus() + "]不允许签收（需接力人员先接货）");
        }
        LocalDateTime now = LocalDateTime.now();
        handover.setStatus(RelayStatus.SIGNED);
        handover.setSignedAt(now);
        handover.setSignBy(signBy);
        handoverRepository.save(handover);

        DeliveryOrder order = handover.getDelivery();
        order.setStatus(DeliveryStatus.SIGNED);
        order.setSignedAt(now);
        orderRepository.save(order);

        boolean onTime = order.getEstimatedDeliveryAt() == null || !now.isAfter(order.getEstimatedDeliveryAt());
        Map<String, Object> payload = basePayload(order, handover);
        payload.put("signBy", signBy);
        payload.put("durationSeconds", Duration.between(order.getCreatedAt(), now).getSeconds());
        payload.put("onTime", onTime);
        payload.put("damageReported", damageReported);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RELAY_SIGNED, EventActor.USER, payload);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.SIGNED, EventActor.USER, payload);

        if (damageReported) {
            applyRelayDamage(order, handover,
                    damageNote == null ? "接力人员送达时发现商品损坏" : damageNote);
        }
        if (!onTime) {
            reportIssue(order, handover, IssueType.DELAY, DeliveryStage.MANUAL_RELAY,
                    "人工接力送达时间晚于预计送达时间 " + order.getEstimatedDeliveryAt());
        }
        return handover;
    }

    /**
     * 异常上报（商品损坏/延迟）：依据交接单当前状态确定所处阶段，按阶段判定责任。
     * 接力阶段确认商品损坏的，重算费用并减免接力服务费。
     */
    @Transactional
    public Map<String, Object> reportIssue(Long handoverId, IssueType issueType, String note) {
        RelayHandover handover = mustGet(handoverId);
        DeliveryOrder order = handover.getDelivery();
        DeliveryStage stage = currentStage(handover, order);
        if (issueType == IssueType.DAMAGE && stage == DeliveryStage.MANUAL_RELAY) {
            applyRelayDamage(order, handover, note);
        } else {
            reportIssue(order, handover, issueType, stage, note);
        }
        Map<String, Object> result = new HashMap<>(basePayload(order, handover));
        result.put("issueType", issueType.name());
        result.put("stage", stage.name());
        result.put("responsibility",
                (issueType == IssueType.DAMAGE ? stage.damageResponsibility() : stage.delayResponsibility()).name());
        return result;
    }

    private void applyRelayDamage(DeliveryOrder order, RelayHandover handover, String note) {
        handover.setDamageReported(true);
        handover.setDamageNote(note);
        // 人工接力阶段损坏：减免接力服务费并重算费用
        FeeCalculator.FeeResult fee = feeCalculator.relayFee(order, latestFloorDelta(order.getId()), true);
        handover.setFee(fee.total());
        handoverRepository.save(handover);
        order.setDeliveryFee(fee.total());
        order.setDeliveryFeeDetail(fee.detail());
        orderRepository.save(order);
        reportIssue(order, handover, IssueType.DAMAGE, DeliveryStage.MANUAL_RELAY, note);

        Map<String, Object> feePayload = basePayload(order, handover);
        feePayload.put("fee", fee.total());
        feePayload.put("feeDetail", fee.detail());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RELAY_FEE_RECALCULATED, EventActor.SYSTEM, feePayload);
    }

    private void reportIssue(DeliveryOrder order, RelayHandover handover, IssueType issueType,
                             DeliveryStage stage, String note) {
        Responsibility responsibility = issueType == IssueType.DAMAGE
                ? stage.damageResponsibility() : stage.delayResponsibility();
        CaseType caseType = switch (issueType) {
            case DAMAGE -> stage == DeliveryStage.USER_PICKUP ? CaseType.PICKUP_DAMAGE : CaseType.RELAY_DAMAGE;
            case DELAY -> stage == DeliveryStage.ROBOT_WAITING ? CaseType.ELEVATOR_FULL : CaseType.RELAY_DELAY;
        };
        String fullNote = stage.label() + "发生" + (issueType == IssueType.DAMAGE ? "商品损坏" : "配送延迟")
                + (note == null ? "" : "：" + note);

        Map<String, Object> payload = basePayload(order, handover);
        payload.put("issueType", issueType.name());
        payload.put("stage", stage.name());
        payload.put("responsibility", responsibility.name());
        payload.put("note", fullNote);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                issueType == IssueType.DAMAGE ? EventType.DAMAGE_REPORTED : EventType.CS_INTERVENTION,
                EventActor.CS, stage.name(), null, payload);
        csService.openCase(order.getId(), caseType, responsibility, fullNote, null);
    }

    private DeliveryStage currentStage(RelayHandover handover, DeliveryOrder order) {
        return switch (handover.getStatus()) {
            case DISPATCHED, COMPARTMENT_OPENED -> DeliveryStage.ROBOT_WAITING;
            case HANDED_OVER -> DeliveryStage.MANUAL_RELAY;
            case SIGNED -> DeliveryStage.USER_PICKUP;
        };
    }

    public List<RelayHandover> listByDelivery(Long deliveryId) {
        return handoverRepository.findByDeliveryIdOrderByIdAsc(deliveryId);
    }

    private int latestFloorDelta(Long deliveryId) {
        return elevatorRepository.findByDeliveryIdOrderByIdDesc(deliveryId).stream()
                .findFirst()
                .map(r -> Math.abs(r.getToFloor() - r.getFromFloor()))
                .orElse(0);
    }

    private void releaseRobotAndCompartment(DeliveryOrder order) {
        Robot robot = order.getRobot();
        if (robot == null) {
            return;
        }
        if (order.getCompartmentNo() != null) {
            compartmentRepository.findByRobotIdAndCompartmentNo(robot.getId(), order.getCompartmentNo())
                    .ifPresent(c -> {
                        if (c.getStatus() == CompartmentStatus.OCCUPIED) {
                            c.setStatus(CompartmentStatus.IDLE);
                            compartmentRepository.save(c);
                        }
                    });
        }
        if (robot.getStatus() == RobotStatus.BUSY) {
            robot.setStatus(RobotStatus.IDLE);
            robotRepository.save(robot);
        }
    }

    private Map<String, Object> basePayload(DeliveryOrder order, RelayHandover handover) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("handoverNo", handover.getHandoverNo());
        payload.put("courier", handover.getCourierName());
        payload.put("compartmentNo", handover.getCompartmentNo());
        payload.put("status", handover.getStatus().name());
        if (order.getDeliveryFee() != null) {
            payload.put("fee", order.getDeliveryFee());
        }
        return payload;
    }

    private RelayHandover mustGet(Long handoverId) {
        return handoverRepository.findById(handoverId)
                .orElseThrow(() -> ApiException.notFound("接力交接单不存在: " + handoverId));
    }

    private DeliveryOrder mustGetOrder(Long deliveryId) {
        return orderRepository.findById(deliveryId)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + deliveryId));
    }

    public enum IssueType {
        DAMAGE, DELAY
    }
}
