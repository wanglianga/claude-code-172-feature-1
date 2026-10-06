package com.example.robotdelivery.liability;

import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.cs.CaseType;
import com.example.robotdelivery.cs.CsCase;
import com.example.robotdelivery.cs.CsService;
import com.example.robotdelivery.cs.Responsibility;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.elevator.ElevatorRequest;
import com.example.robotdelivery.elevator.ElevatorRequestRepository;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.relay.RelayHandover;
import com.example.robotdelivery.relay.RelayHandoverRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三阶段责任判定。
 *
 * 配送异常（商品损坏 / 配送延迟）发生后，按统一事件时间线把履约过程切分为三段：
 * 1) 机器人等待阶段 ROBOT_WAIT：机器人在楼下等待电梯（含连续满载），商品由机器人保管；
 * 2) 人工接力阶段 RELAY：配送员现场接货到送达，商品由平台配送员搬运；
 * 3) 用户取件阶段 USER_PICKUP：到门/下楼通知后用户开仓取件。
 * 按异常发生阶段认定责任：
 * - 商品损坏：机器人等待→机器人厂商；人工接力→平台运营；用户取件→用户；
 * - 配送延迟：机器人等待→电梯系统；人工接力（超接力时效）→平台运营；用户取件（超时不取）→用户。
 */
@Service
public class LiabilityService {

    private final DeliveryOrderRepository orderRepository;
    private final RelayHandoverRepository relayRepository;
    private final ElevatorRequestRepository elevatorRepository;
    private final DeliveryEventService eventService;
    private final CsService csService;
    private final DeliveryProperties properties;

    public LiabilityService(DeliveryOrderRepository orderRepository,
                            RelayHandoverRepository relayRepository,
                            ElevatorRequestRepository elevatorRepository,
                            DeliveryEventService eventService,
                            CsService csService,
                            DeliveryProperties properties) {
        this.orderRepository = orderRepository;
        this.relayRepository = relayRepository;
        this.elevatorRepository = elevatorRepository;
        this.eventService = eventService;
        this.csService = csService;
        this.properties = properties;
    }

    /**
     * 责任判定（不落工单，只返回判定结果与三阶段时间线，供客服核查）。
     *
     * @param occurredStage 可选：客服根据现场情况指定异常发生阶段；为空时系统按时间线推断
     */
    public Map<String, Object> judge(Long deliveryId, LiabilityIssue issue, LiabilityStage occurredStage) {
        DeliveryOrder order = orderRepository.findById(deliveryId)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + deliveryId));
        RelayHandover handover = relayRepository.findByDeliveryId(deliveryId).orElse(null);
        List<ElevatorRequest> elevatorRequests =
                elevatorRepository.findByDeliveryIdOrderByIdDesc(deliveryId);

        Map<String, Object> timeline = buildTimeline(order, handover, elevatorRequests);
        LiabilityStage stage = occurredStage != null && occurredStage != LiabilityStage.NONE
                ? occurredStage
                : inferStage(issue, order, handover, (Map<String, Object>) timeline.get("stages"));
        Responsibility responsibility = responsibilityOf(issue, stage);
        String basis = basis(issue, stage, order, handover, (Map<String, Object>) timeline.get("stages"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deliveryId", deliveryId);
        result.put("orderNo", order.getOrderNo());
        result.put("issue", issue.name());
        result.put("stage", stage.name());
        result.put("responsibility", responsibility.name());
        result.put("basis", basis);
        result.put("timeline", timeline);
        return result;
    }

    /**
     * 客服登记异常并完成责任判定：开立工单、写入责任与阶段，并发送 LIABILITY_JUDGED 事件。
     */
    @Transactional
    public Map<String, Object> report(Long deliveryId, LiabilityIssue issue,
                                      LiabilityStage occurredStage, String note) {
        Map<String, Object> judgment = judge(deliveryId, issue, occurredStage);
        Responsibility responsibility = Responsibility.valueOf((String) judgment.get("responsibility"));
        LiabilityStage stage = LiabilityStage.valueOf((String) judgment.get("stage"));
        CaseType caseType = issue == LiabilityIssue.GOODS_DAMAGED
                ? CaseType.GOODS_DAMAGED : CaseType.DELIVERY_DELAY;
        String fullNote = "三阶段责任判定：阶段=" + stage.name()
                + "，责任=" + responsibility.name()
                + "，依据=" + judgment.get("basis")
                + (note == null || note.isBlank() ? "" : "；备注=" + note);
        CsCase csCase = csService.openCase(deliveryId, caseType, responsibility, fullNote, null);

        Map<String, Object> eventPayload = new LinkedHashMap<>(judgment);
        eventPayload.put("caseNo", csCase.getCaseNo());
        eventPayload.put("note", note);
        eventService.emit(deliveryId, orderRepository.findById(deliveryId)
                        .map(o -> o.getCommunity().getId()).orElse(null),
                EventType.LIABILITY_JUDGED, EventActor.CS, stage.name(), null, eventPayload);
        judgment.put("caseNo", csCase.getCaseNo());
        return judgment;
    }

    // ---- 阶段推断与责任映射 ----

    private LiabilityStage inferStage(LiabilityIssue issue, DeliveryOrder order,
                                      RelayHandover handover, Map<String, Object> stages) {
        if (issue == LiabilityIssue.GOODS_DAMAGED) {
            // 默认损坏环节：有接力交接且用户在接力送达后才接触商品 → 接力阶段；否则按机器人保管阶段
            return handover != null && handover.getHandedAt() != null
                    ? LiabilityStage.RELAY : LiabilityStage.ROBOT_WAIT;
        }
        // 延迟：依次判断耗时发生在哪一段
        if (Boolean.TRUE.equals(stages.get("relayExceededSla"))) {
            return LiabilityStage.RELAY;
        }
        if (Boolean.TRUE.equals(stages.get("pickupExceededTimeout"))) {
            return LiabilityStage.USER_PICKUP;
        }
        if (((Number) stages.getOrDefault("elevatorFullCount", 0)).intValue() > 0) {
            return LiabilityStage.ROBOT_WAIT;
        }
        return LiabilityStage.NONE;
    }

    private Responsibility responsibilityOf(LiabilityIssue issue, LiabilityStage stage) {
        if (stage == LiabilityStage.NONE) {
            return Responsibility.NONE;
        }
        if (issue == LiabilityIssue.GOODS_DAMAGED) {
            return switch (stage) {
                case ROBOT_WAIT -> Responsibility.ROBOT_VENDOR;
                case RELAY -> Responsibility.PLATFORM;
                case USER_PICKUP -> Responsibility.USER;
                default -> Responsibility.NONE;
            };
        }
        return switch (stage) {
            case ROBOT_WAIT -> Responsibility.ELEVATOR_SYSTEM;
            case RELAY -> Responsibility.PLATFORM;
            case USER_PICKUP -> Responsibility.USER;
            default -> Responsibility.NONE;
        };
    }

    private String basis(LiabilityIssue issue, LiabilityStage stage, DeliveryOrder order,
                         RelayHandover handover, Map<String, Object> stages) {
        if (stage == LiabilityStage.NONE) {
            return "各阶段耗时与交接记录均正常，未发现可归责的延迟环节";
        }
        if (issue == LiabilityIssue.GOODS_DAMAGED) {
            return switch (stage) {
                case ROBOT_WAIT -> "商品在机器人载物仓保管阶段（楼下等待电梯）损坏，由机器人设备方负责";
                case RELAY -> "商品在人工接力阶段（配送员" + (handover == null ? "" : handover.getCourierName())
                        + "接货至送达）损坏，由平台负责";
                case USER_PICKUP -> "商品在用户开仓取件阶段损坏，由用户自行负责";
                default -> "";
            };
        }
        return switch (stage) {
            case ROBOT_WAIT -> "电梯连续满载" + stages.get("elevatorFullCount")
                    + "次，机器人等待阶段造成延迟，由电梯系统负责";
            case RELAY -> "人工接力耗时" + stages.get("relaySeconds") + "秒，超过接力时效"
                    + stages.get("relaySlaSeconds") + "秒，由平台负责";
            case USER_PICKUP -> "通知取件后用户耗时" + stages.get("pickupSeconds")
                    + "秒才取件，超过取件时限，由用户负责";
            default -> "";
        };
    }

    /** 构建三阶段时间线与量化指标 */
    private Map<String, Object> buildTimeline(DeliveryOrder order, RelayHandover handover,
                                              List<ElevatorRequest> elevatorRequests) {
        long fullCount = elevatorRequests.stream()
                .filter(r -> r.getStatus().name().equals("FULL")).count();

        LocalDateTime firstElevatorRequest = elevatorRequests.stream()
                .map(ElevatorRequest::getRequestedAt).min(LocalDateTime::compareTo).orElse(null);
        // 机器人等待阶段终点：乘梯完成，或接力接货
        LocalDateTime waitEnd = handover != null && handover.getHandedAt() != null
                ? handover.getHandedAt()
                : elevatorRequests.stream()
                .filter(r -> r.getStatus().name().equals("COMPLETED"))
                .map(ElevatorRequest::getResolvedAt)
                .findFirst().orElse(null);
        Long robotWaitSeconds = firstElevatorRequest != null && waitEnd != null
                ? Duration.between(firstElevatorRequest, waitEnd).getSeconds() : null;

        Long relaySeconds = handover != null && handover.getHandedAt() != null
                && handover.getDeliveredAt() != null
                ? Duration.between(handover.getHandedAt(), handover.getDeliveredAt()).getSeconds() : null;
        boolean relayExceeded = relaySeconds != null && relaySeconds > properties.getRelaySlaSeconds();

        LocalDateTime notifiedAt = order.getDownstairsNotifiedAt() != null
                ? order.getDownstairsNotifiedAt() : order.getNotifiedAt();
        Long pickupSeconds = notifiedAt != null && order.getSignedAt() != null
                ? Duration.between(notifiedAt, order.getSignedAt()).getSeconds() : null;
        boolean pickupExceeded = pickupSeconds != null
                && pickupSeconds > properties.getPickupTimeoutSeconds();

        Map<String, Object> stages = new LinkedHashMap<>();
        stages.put("elevatorFullCount", fullCount);
        stages.put("robotWaitSeconds", robotWaitSeconds);
        stages.put("relaySeconds", relaySeconds);
        stages.put("relaySlaSeconds", properties.getRelaySlaSeconds());
        stages.put("relayExceededSla", relayExceeded);
        stages.put("pickupSeconds", pickupSeconds);
        stages.put("pickupTimeoutSeconds", properties.getPickupTimeoutSeconds());
        stages.put("pickupExceededTimeout", pickupExceeded);
        stages.put("signedLate", order.getSignedAt() != null && order.getEstimatedDeliveryAt() != null
                && order.getSignedAt().isAfter(order.getEstimatedDeliveryAt()));

        Map<String, Object> timeline = new LinkedHashMap<>();
        Map<String, Object> robotWait = new LinkedHashMap<>();
        robotWait.put("firstElevatorRequestedAt", firstElevatorRequest);
        robotWait.put("waitEndAt", waitEnd);
        robotWait.put("elevatorFullCount", fullCount);
        robotWait.put("waitSeconds", robotWaitSeconds);
        timeline.put("robotWait", robotWait);

        Map<String, Object> relay = new LinkedHashMap<>();
        if (handover != null) {
            relay.put("courierId", handover.getCourierId());
            relay.put("courierName", handover.getCourierName());
            relay.put("handoverLocation", handover.getHandoverLocation());
            relay.put("compartmentOpenedAt", handover.getCompartmentOpenedAt());
            relay.put("handedAt", handover.getHandedAt());
            relay.put("deliveredAt", handover.getDeliveredAt());
            relay.put("relaySeconds", relaySeconds);
            relay.put("exceededSla", relayExceeded);
        }
        timeline.put("relay", relay);

        Map<String, Object> userPickup = new LinkedHashMap<>();
        userPickup.put("notifiedAt", notifiedAt);
        userPickup.put("signedAt", order.getSignedAt());
        userPickup.put("pickupSeconds", pickupSeconds);
        userPickup.put("exceededTimeout", pickupExceeded);
        timeline.put("userPickup", userPickup);
        timeline.put("stages", stages);
        return timeline;
    }
}
