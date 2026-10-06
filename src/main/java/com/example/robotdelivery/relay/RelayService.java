package com.example.robotdelivery.relay;

import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.elevator.ElevatorRequest;
import com.example.robotdelivery.elevator.ElevatorRequestRepository;
import com.example.robotdelivery.elevator.ElevatorStatus;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.notification.NotificationService;
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

/**
 * 人工接力编排：电梯连续满载/低电量/设备故障等场景下，机器人无法继续乘梯时，
 * 改由人工配送员到机器人等待位置现场开启载物仓接货，完成最后一段配送。
 *
 * 交接全过程记录接力人员身份（工号/姓名/电话）、接货时间、仓门开启时间与用户签收；
 * 接力时订单状态、载物仓交接与配送费用同步重新计算。
 */
@Service
public class RelayService {

    private final RelayHandoverRepository relayRepository;
    private final DeliveryOrderRepository orderRepository;
    private final ElevatorRequestRepository elevatorRepository;
    private final CargoCompartmentRepository compartmentRepository;
    private final RobotRepository robotRepository;
    private final DeliveryEventService eventService;
    private final NotificationService notificationService;
    private final FeeService feeService;

    public RelayService(RelayHandoverRepository relayRepository,
                        DeliveryOrderRepository orderRepository,
                        ElevatorRequestRepository elevatorRepository,
                        CargoCompartmentRepository compartmentRepository,
                        RobotRepository robotRepository,
                        DeliveryEventService eventService,
                        NotificationService notificationService,
                        FeeService feeService) {
        this.relayRepository = relayRepository;
        this.orderRepository = orderRepository;
        this.elevatorRepository = elevatorRepository;
        this.compartmentRepository = compartmentRepository;
        this.robotRepository = robotRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.feeService = feeService;
    }

    /**
     * 发起人工接力（自动策略触发或运营/客服人工发起）：
     * 作废未完成的电梯请求，订单进入 RELAYING，按楼层重新计算费用，记录交接单并通知用户。
     * 此时商品仍在机器人载物仓内，机器人保持占用，直到配送员现场接货。
     */
    @Transactional
    public RelayHandover dispatch(DeliveryOrder order, RelayTriggerReason reason,
                                  String courierId, String courierName, String courierPhone,
                                  String handoverLocation) {
        if (DeliveryStatus.TERMINAL.contains(order.getStatus())) {
            throw ApiException.conflict("订单已终结: " + order.getStatus());
        }
        if (relayRepository.findByDeliveryId(order.getId()).isPresent()) {
            throw ApiException.conflict("该订单已存在人工接力交接单");
        }
        cancelElevatorRequests(order);

        int floorsClimbed = resolveFloorsClimbed(order);
        FeeService.FeeBreakdown fee = feeService.recalcForRelay(order, floorsClimbed, reason);
        order.setDeliveryFeeCents(fee.robotFeeCents());
        order.setRelayFeeCents(fee.relaySurchargeCents());
        order.setTotalFeeCents(fee.totalFeeCents());
        order.setFeeDetail(fee.detail());
        if (courierName != null && !courierName.isBlank()) {
            order.setRelayCourier(courierName);
        }
        order.setStatus(DeliveryStatus.RELAYING);
        orderRepository.save(order);

        RelayHandover handover = new RelayHandover();
        handover.setDelivery(order);
        handover.setCourierId(courierId == null || courierId.isBlank() ? "PENDING" : courierId);
        handover.setCourierName(courierName == null || courierName.isBlank() ? "待派发" : courierName);
        handover.setCourierPhone(courierPhone);
        handover.setHandoverLocation(handoverLocation == null || handoverLocation.isBlank()
                ? order.getBuilding().getName() + "1层大堂" : handoverLocation);
        handover.setCompartmentNo(order.getCompartmentNo());
        handover.setStatus(RelayStatus.DISPATCHED);
        handover.setTriggerReason(reason);
        handover.setOriginalFeeCents(Math.max(0, fee.robotFeeCents()));
        handover.setRelayFeeCents(fee.relaySurchargeCents());
        handover.setTotalFeeCents(fee.totalFeeCents());
        relayRepository.save(handover);

        Map<String, Object> payload = basePayload(order);
        payload.put("triggerReason", reason.name());
        payload.put("courierId", handover.getCourierId());
        payload.put("courier", handover.getCourierName());
        payload.put("courierPhone", courierPhone);
        payload.put("handoverLocation", handover.getHandoverLocation());
        payload.put("floorsClimbed", floorsClimbed);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.MANUAL_RELAY, EventActor.OPS, reason.name(), null, payload);

        emitFeeEvent(order, reason, floorsClimbed, fee);
        notificationService.notifyRelay(order, handover.getCourierName(),
                reasonText(reason), fee.surchargeWaived());
        return handover;
    }

    /**
     * 配送员到达现场，核验身份后开启机器人载物仓并取走商品（仓门开启 + 接货）。
     * 系统记录接力人员身份、仓门开启时间、接货时间；随后释放机器人与载物仓。
     */
    @Transactional
    public RelayHandover acceptHandover(Long deliveryId, String courierId, String courierName,
                                        String courierPhone) {
        RelayHandover handover = mustGet(deliveryId);
        if (handover.getStatus() != RelayStatus.DISPATCHED) {
            throw ApiException.conflict("交接单状态[" + handover.getStatus() + "]不允许接货");
        }
        boolean preAssigned = !"PENDING".equals(handover.getCourierId());
        if (preAssigned) {
            // 派单时已指定配送员：现场身份必须与派单一致
            if (courierId == null || !courierId.equals(handover.getCourierId())) {
                throw ApiException.conflict("现场配送员工号与派单不一致，禁止开启载物仓");
            }
        } else {
            if (courierId == null || courierId.isBlank() || courierName == null || courierName.isBlank()) {
                throw ApiException.badRequest("自动派单的接力必须登记配送员工号与姓名");
            }
            handover.setCourierId(courierId);
            handover.setCourierName(courierName);
        }
        if (courierPhone != null && !courierPhone.isBlank()) {
            handover.setCourierPhone(courierPhone);
        }

        LocalDateTime now = LocalDateTime.now();
        handover.setCompartmentOpenedAt(now);
        relayRepository.save(handover);
        DeliveryOrder order = handover.getDelivery();

        Map<String, Object> openPayload = basePayload(order);
        openPayload.put("courierId", handover.getCourierId());
        openPayload.put("courier", handover.getCourierName());
        openPayload.put("compartmentNo", handover.getCompartmentNo());
        openPayload.put("handoverLocation", handover.getHandoverLocation());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RELAY_COMPARTMENT_OPENED, EventActor.COURIER, null, null, openPayload);

        // 仓门现场开启、商品取出 = 接货完成
        handover.setHandedAt(now);
        handover.setStatus(RelayStatus.HANDED_OVER);
        relayRepository.save(handover);
        order.setRelayCourier(handover.getCourierName());
        orderRepository.save(order);
        releaseRobotAndCompartment(order);

        Map<String, Object> handPayload = basePayload(order);
        handPayload.put("courierId", handover.getCourierId());
        handPayload.put("courier", handover.getCourierName());
        handPayload.put("courierPhone", handover.getCourierPhone());
        handPayload.put("handoverLocation", handover.getHandoverLocation());
        handPayload.put("handedAt", now.toString());
        handPayload.put("compartmentOpenedAt", now.toString());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RELAY_HANDOVER, EventActor.COURIER, null, null, handPayload);
        return handover;
    }

    /** 配送员把商品送达用户门口：进入等待用户取件并发送取件通知 */
    @Transactional
    public RelayHandover deliver(Long deliveryId) {
        RelayHandover handover = mustGet(deliveryId);
        if (handover.getStatus() != RelayStatus.HANDED_OVER) {
            throw ApiException.conflict("交接单状态[" + handover.getStatus() + "]不允许送达（需先现场接货）");
        }
        LocalDateTime now = LocalDateTime.now();
        handover.setDeliveredAt(now);
        handover.setStatus(RelayStatus.DELIVERED);
        relayRepository.save(handover);

        DeliveryOrder order = handover.getDelivery();
        order.setStatus(DeliveryStatus.WAITING_PICKUP);
        order.setNotifiedAt(now);
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("courier", handover.getCourierName());
        payload.put("handedAt", handover.getHandedAt().toString());
        payload.put("deliveredAt", now.toString());
        payload.put("relaySeconds", Duration.between(handover.getHandedAt(), now).getSeconds());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RELAY_DELIVERED, EventActor.COURIER, null, null, payload);
        notificationService.notifyArrival(order);

        Map<String, Object> notifyPayload = basePayload(order);
        notifyPayload.put("channels", "SMS,MINI_PROGRAM");
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.USER_NOTIFIED, EventActor.SYSTEM, "RELAY", null, notifyPayload);
        return handover;
    }

    /** 用户签收：记录签收人与签收时间，订单完成 */
    @Transactional
    public RelayHandover sign(Long deliveryId, String signBy) {
        RelayHandover handover = mustGet(deliveryId);
        if (handover.getStatus() != RelayStatus.DELIVERED) {
            throw ApiException.conflict("交接单状态[" + handover.getStatus() + "]不允许签收（需配送员先送达）");
        }
        LocalDateTime now = LocalDateTime.now();
        handover.setSignBy(signBy);
        handover.setSignedAt(now);
        handover.setStatus(RelayStatus.SIGNED);
        relayRepository.save(handover);

        DeliveryOrder order = handover.getDelivery();
        order.setStatus(DeliveryStatus.SIGNED);
        order.setSignedAt(now);
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("signBy", signBy);
        payload.put("courier", handover.getCourierName());
        payload.put("deliveryFeeCents", order.getDeliveryFeeCents());
        payload.put("relayFeeCents", order.getRelayFeeCents());
        payload.put("totalFeeCents", order.getTotalFeeCents());
        payload.put("onTime", order.getEstimatedDeliveryAt() == null
                || !now.isAfter(order.getEstimatedDeliveryAt()));
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.SIGNED, EventActor.USER, "MANUAL_RELAY", null, payload);
        return handover;
    }

    /** 电梯连续满载策略要求用户下楼：发送下楼取件通知（订单状态由调用方置位） */
    @Transactional
    public void notifyDownstairs(DeliveryOrder order, Integer fromFloor, int fullFailCount) {
        notificationService.notifyDownstairs(order, fromFloor, fullFailCount);
        Map<String, Object> payload = basePayload(order);
        payload.put("channels", "SMS,MINI_PROGRAM");
        payload.put("fullFailCount", fullFailCount);
        payload.put("pickupAtFloor", fromFloor);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.USER_NOTIFIED, EventActor.SYSTEM, "DOWNSTAIRS", null, payload);
    }

    public RelayHandover getByDelivery(Long deliveryId) {
        return mustGet(deliveryId);
    }

    public List<RelayHandover> list(String status) {
        if (status == null || status.isBlank()) {
            return relayRepository.findAll();
        }
        return relayRepository.findByStatus(RelayStatus.valueOf(status));
    }

    // ---- internals ----

    private void emitFeeEvent(DeliveryOrder order, RelayTriggerReason reason,
                              int floorsClimbed, FeeService.FeeBreakdown fee) {
        Map<String, Object> payload = basePayload(order);
        payload.put("triggerReason", reason.name());
        payload.put("floorsClimbed", floorsClimbed);
        payload.put("deliveryFeeCents", fee.robotFeeCents());
        payload.put("relayFeeCents", fee.relaySurchargeCents());
        payload.put("totalFeeCents", fee.totalFeeCents());
        payload.put("surchargeWaived", fee.surchargeWaived());
        payload.put("feeDetail", fee.detail());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.FEE_RECALCULATED, EventActor.SYSTEM, reason.name(), null, payload);
    }

    /** 乘梯楼层数：优先取最近一次电梯请求，否则按房号推断 */
    private int resolveFloorsClimbed(DeliveryOrder order) {
        return elevatorRepository.findByDeliveryIdOrderByIdDesc(order.getId()).stream()
                .findFirst()
                .map(r -> Math.max(0, r.getToFloor() - r.getFromFloor()))
                .orElse(Math.max(0, FeeService.floorFromRoomNo(order.getRoomNo()) - 1));
    }

    private void cancelElevatorRequests(DeliveryOrder order) {
        elevatorRepository.findByDeliveryIdOrderByIdDesc(order.getId()).stream()
                .filter(r -> r.getStatus() == ElevatorStatus.PENDING
                        || r.getStatus() == ElevatorStatus.ASSIGNED)
                .forEach(r -> {
                    r.setStatus(ElevatorStatus.CANCELLED);
                    r.setResolvedAt(LocalDateTime.now());
                    elevatorRepository.save(r);
                });
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

    private Map<String, Object> basePayload(DeliveryOrder order) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("orderType", order.getOrderType().name());
        payload.put("building", order.getBuilding().getName());
        payload.put("roomNo", order.getRoomNo());
        payload.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        payload.put("compartmentNo", order.getCompartmentNo());
        payload.put("totalFeeCents", order.getTotalFeeCents());
        if (order.getRelayCourier() != null) {
            payload.put("relayCourier", order.getRelayCourier());
        }
        return payload;
    }

    private RelayHandover mustGet(Long deliveryId) {
        return relayRepository.findByDeliveryId(deliveryId)
                .orElseThrow(() -> ApiException.notFound("人工接力交接单不存在: deliveryId=" + deliveryId));
    }

    private String reasonText(RelayTriggerReason reason) {
        return switch (reason) {
            case ELEVATOR_FULL -> "楼栋电梯连续满载";
            case LOW_BATTERY -> "机器人电量不足";
            case ROBOT_FAULT -> "机器人设备故障";
            case MANUAL -> "配送调度需要";
        };
    }
}
