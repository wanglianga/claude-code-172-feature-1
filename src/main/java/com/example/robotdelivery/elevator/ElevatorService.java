package com.example.robotdelivery.elevator;

import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import com.example.robotdelivery.relay.RelayService;
import com.example.robotdelivery.relay.RelayTriggerReason;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 电梯系统协同：机器人进入楼栋后请求电梯选择楼层。
 * 电梯满载时自动重新排队（路线调整）；连续满载达到阈值（默认3次）后触发等待策略，
 * 综合订单时效（预计送达/冷藏截止）、机器人电量、目标楼层高度判定：
 * 继续等待 / 改人工接力 / 通知用户下楼取件。
 */
@Service
public class ElevatorService {

    /** 连续满载后的策略 */
    public static final String STRATEGY_CONTINUE_WAIT = "CONTINUE_WAIT";
    public static final String STRATEGY_MANUAL_RELAY = "MANUAL_RELAY";
    public static final String STRATEGY_USER_DOWNSTAIRS = "USER_DOWNSTAIRS";

    private final ElevatorRequestRepository elevatorRepository;
    private final DeliveryOrderRepository orderRepository;
    private final DeliveryEventService eventService;
    private final DeliveryProperties properties;
    private final RelayService relayService;

    public ElevatorService(ElevatorRequestRepository elevatorRepository,
                           DeliveryOrderRepository orderRepository,
                           DeliveryEventService eventService,
                           DeliveryProperties properties,
                           @Lazy RelayService relayService) {
        this.elevatorRepository = elevatorRepository;
        this.orderRepository = orderRepository;
        this.eventService = eventService;
        this.properties = properties;
        this.relayService = relayService;
    }

    @Transactional
    public ElevatorRequest request(Long deliveryId, Integer fromFloor, Integer toFloor) {
        DeliveryOrder order = mustGet(deliveryId);
        if (order.getStatus() != DeliveryStatus.ENTERING) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许请求电梯（需先通过门禁进入楼栋）");
        }
        ElevatorRequest request = createRequest(order, fromFloor, toFloor);

        order.setStatus(DeliveryStatus.ELEVATOR_WAIT);
        orderRepository.save(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_REQUESTED, EventActor.ROBOT, payload(order, request));
        return request;
    }

    /** 电梯系统派梯 */
    @Transactional
    public ElevatorRequest assign(Long requestId, String carId) {
        ElevatorRequest request = mustGetRequest(requestId);
        if (request.getStatus() != ElevatorStatus.PENDING) {
            throw ApiException.conflict("电梯请求状态[" + request.getStatus() + "]不允许派梯");
        }
        request.setStatus(ElevatorStatus.ASSIGNED);
        request.setCarId(carId);
        request.setResolvedAt(LocalDateTime.now());
        elevatorRepository.save(request);

        DeliveryOrder order = request.getDelivery();
        Map<String, Object> payload = payload(order, request);
        payload.put("carId", carId);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_ASSIGNED, EventActor.ELEVATOR, payload);
        return request;
    }

    /**
     * 电梯系统上报满载。
     * 前两次：机器人等待下一趟并自动重新请求（路线调整）；
     * 连续第 N 次（默认3次）：按订单时效、电量、楼层高度判定等待策略。
     */
    @Transactional
    public ElevatorRequest full(Long requestId) {
        ElevatorRequest request = mustGetRequest(requestId);
        if (request.getStatus() != ElevatorStatus.PENDING) {
            throw ApiException.conflict("电梯请求状态[" + request.getStatus() + "]不允许标记满载");
        }
        request.setStatus(ElevatorStatus.FULL);
        request.setResolvedAt(LocalDateTime.now());
        elevatorRepository.save(request);

        DeliveryOrder order = request.getDelivery();
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_FULL, EventActor.ELEVATOR, payload(order, request));

        int failCount = consecutiveFullCount(order.getId());
        order.setFullFailCount(failCount);
        orderRepository.save(order);

        if (failCount < properties.getElevatorFullThreshold()) {
            // 路线调整：等待下一趟电梯，自动重新请求
            Map<String, Object> adjustPayload = payload(order, request);
            adjustPayload.put("detail", "电梯满载，机器人等待下一趟并重新请求（连续第" + failCount + "次）");
            adjustPayload.put("fullFailCount", failCount);
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.ROUTE_ADJUSTED, EventActor.SYSTEM, "ELEVATOR_FULL", null, adjustPayload);

            ElevatorRequest retry = createRequest(order, request.getFromFloor(), request.getToFloor());
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.ELEVATOR_REQUESTED, EventActor.ROBOT, payload(order, retry));
            return retry;
        }

        // 连续满载达阈值：策略判定
        FullFactors factors = evaluate(order, request);
        String strategy = decide(factors);
        order.setFullStrategy(strategy);
        orderRepository.save(order);

        emitStrategyEvent(order, request, failCount, factors, strategy);
        return switch (strategy) {
            case STRATEGY_CONTINUE_WAIT -> continueWait(order, request);
            case STRATEGY_USER_DOWNSTAIRS -> askUserDownstairs(order, request, failCount);
            default -> dispatchRelay(order, request);
        };
    }

    /** 乘梯完成，到达目标楼层；连续满载计数清零 */
    @Transactional
    public ElevatorRequest complete(Long requestId) {
        ElevatorRequest request = mustGetRequest(requestId);
        if (request.getStatus() != ElevatorStatus.ASSIGNED) {
            throw ApiException.conflict("电梯请求状态[" + request.getStatus() + "]不允许完成（需先派梯）");
        }
        request.setStatus(ElevatorStatus.COMPLETED);
        request.setResolvedAt(LocalDateTime.now());
        elevatorRepository.save(request);

        DeliveryOrder order = request.getDelivery();
        order.setStatus(DeliveryStatus.ON_FLOOR);
        order.setFullFailCount(0);
        order.setFullStrategy(null);
        orderRepository.save(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_COMPLETED, EventActor.ELEVATOR, payload(order, request));
        return request;
    }

    // ---- 连续满载后的三种策略 ----

    /** 策略一：继续等待下一趟（低楼层、时效宽松、电量充足） */
    private ElevatorRequest continueWait(DeliveryOrder order, ElevatorRequest request) {
        Map<String, Object> adjustPayload = payload(order, request);
        adjustPayload.put("detail", "连续满载但时效宽松、电量充足且为低楼层，机器人继续等待下一趟");
        adjustPayload.put("strategy", STRATEGY_CONTINUE_WAIT);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ROUTE_ADJUSTED, EventActor.SYSTEM, "ELEVATOR_FULL_STRATEGY", null, adjustPayload);
        ElevatorRequest retry = createRequest(order, request.getFromFloor(), request.getToFloor());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_REQUESTED, EventActor.ROBOT, payload(order, retry));
        return retry;
    }

    /** 策略三：通知用户下楼到大堂取件（高楼层，上行电梯持续拥挤，下楼反而更快） */
    private ElevatorRequest askUserDownstairs(DeliveryOrder order, ElevatorRequest request, int failCount) {
        cancelPendingRequests(order);
        order.setStatus(DeliveryStatus.WAITING_DOWNSTAIRS);
        order.setDownstairsNotifiedAt(LocalDateTime.now());
        orderRepository.save(order);
        relayService.notifyDownstairs(order, request.getFromFloor(), failCount);
        return request;
    }

    /** 策略二：改人工接力（时效紧迫，或机器人电量不足无法继续等待） */
    private ElevatorRequest dispatchRelay(DeliveryOrder order, ElevatorRequest request) {
        relayService.dispatch(order, RelayTriggerReason.ELEVATOR_FULL,
                null, null, null,
                order.getBuilding().getName() + Math.max(1, request.getFromFloor()) + "层大堂");
        return request;
    }

    // ---- 策略判定因子 ----

    /**
     * 判定因子：
     * - urgent 时效紧迫：距预计送达（或冷藏截止）不足 urgentWindowSeconds（含已超时）
     * - lowBattery 电量不足：机器人当前电量低于低电量阈值
     * - highFloor 高楼：目标楼层不低于 highFloorThreshold
     */
    private FullFactors evaluate(DeliveryOrder order, ElevatorRequest request) {
        LocalDateTime now = LocalDateTime.now();
        boolean urgent;
        LocalDateTime deadline;
        if (order.isColdChain() && order.getColdChainDeadline() != null) {
            deadline = order.getColdChainDeadline();
        } else {
            deadline = order.getEstimatedDeliveryAt();
        }
        urgent = deadline != null
                && !deadline.isAfter(now.plusSeconds(properties.getUrgentWindowSeconds()));

        Integer battery = order.getRobot() == null ? null : order.getRobot().getBattery();
        boolean lowBattery = battery != null && battery < properties.getLowBatteryThreshold();
        boolean highFloor = request.getToFloor() >= properties.getHighFloorThreshold();
        return new FullFactors(deadline, battery, request.getFromFloor(), request.getToFloor(),
                urgent, lowBattery, highFloor);
    }

    /**
     * 决策规则（与因子共同写入事件，便于追溯）：
     * 1. 时效紧迫 或 电量不足 → 人工接力（最快恢复履约，机器人不能继续占位等待）；
     * 2. 时效宽松、电量充足但目标楼层高 → 通知用户下楼（高区上行持续拥挤，下行宽松，下楼取件更快）；
     * 3. 时效宽松、电量充足、低楼层 → 继续等待下一趟。
     */
    private String decide(FullFactors f) {
        if (f.urgent() || f.lowBattery()) {
            return STRATEGY_MANUAL_RELAY;
        }
        if (f.highFloor()) {
            return STRATEGY_USER_DOWNSTAIRS;
        }
        return STRATEGY_CONTINUE_WAIT;
    }

    private void emitStrategyEvent(DeliveryOrder order, ElevatorRequest request,
                                   int failCount, FullFactors f, String strategy) {
        Map<String, Object> payload = payload(order, request);
        payload.put("fullFailCount", failCount);
        payload.put("strategy", strategy);
        payload.put("decisionRule", "时效紧迫或电量不足→人工接力；高楼层→用户下楼；低楼层→继续等待");
        Map<String, Object> factorMap = new HashMap<>();
        factorMap.put("urgent", f.urgent());
        factorMap.put("lowBattery", f.lowBattery());
        factorMap.put("highFloor", f.highFloor());
        factorMap.put("battery", f.battery());
        factorMap.put("fromFloor", f.fromFloor());
        factorMap.put("toFloor", f.toFloor());
        factorMap.put("deadline", f.deadline() == null ? null : f.deadline().toString());
        factorMap.put("urgentWindowSeconds", properties.getUrgentWindowSeconds());
        factorMap.put("lowBatteryThreshold", properties.getLowBatteryThreshold());
        factorMap.put("highFloorThreshold", properties.getHighFloorThreshold());
        payload.put("factors", factorMap);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_FULL_STRATEGY, EventActor.SYSTEM, strategy, null, payload);
    }

    /** 连续满载次数：沿该配送单的电梯请求倒序，遇到最近一次乘梯完成/作废之前的满载不计入 */
    private int consecutiveFullCount(Long deliveryId) {
        List<ElevatorRequest> requests = elevatorRepository.findByDeliveryIdOrderByIdDesc(deliveryId);
        int count = 0;
        for (ElevatorRequest r : requests) {
            if (r.getStatus() == ElevatorStatus.COMPLETED || r.getStatus() == ElevatorStatus.CANCELLED) {
                break;
            }
            if (r.getStatus() == ElevatorStatus.FULL) {
                count++;
            }
        }
        return count;
    }

    private void cancelPendingRequests(DeliveryOrder order) {
        elevatorRepository.findByDeliveryIdOrderByIdDesc(order.getId()).stream()
                .filter(r -> r.getStatus() == ElevatorStatus.PENDING || r.getStatus() == ElevatorStatus.ASSIGNED)
                .forEach(r -> {
                    r.setStatus(ElevatorStatus.CANCELLED);
                    r.setResolvedAt(LocalDateTime.now());
                    elevatorRepository.save(r);
                });
    }

    private ElevatorRequest createRequest(DeliveryOrder order, Integer fromFloor, Integer toFloor) {
        ElevatorRequest request = new ElevatorRequest();
        request.setDelivery(order);
        request.setBuildingId(order.getBuilding().getId());
        request.setFromFloor(fromFloor);
        request.setToFloor(toFloor);
        request.setStatus(ElevatorStatus.PENDING);
        return elevatorRepository.save(request);
    }

    private Map<String, Object> payload(DeliveryOrder order, ElevatorRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", request.getId());
        payload.put("orderNo", order.getOrderNo());
        payload.put("building", order.getBuilding().getName());
        payload.put("fromFloor", request.getFromFloor());
        payload.put("toFloor", request.getToFloor());
        if (request.getCarId() != null) {
            payload.put("carId", request.getCarId());
        }
        return payload;
    }

    private DeliveryOrder mustGet(Long deliveryId) {
        return orderRepository.findById(deliveryId)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + deliveryId));
    }

    private ElevatorRequest mustGetRequest(Long requestId) {
        return elevatorRepository.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("电梯请求不存在: " + requestId));
    }

    /** 策略判定因子快照 */
    private record FullFactors(LocalDateTime deadline, Integer battery, int fromFloor, int toFloor,
                               boolean urgent, boolean lowBattery, boolean highFloor) {
    }
}
