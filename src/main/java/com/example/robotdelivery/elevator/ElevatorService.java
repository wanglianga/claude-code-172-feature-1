package com.example.robotdelivery.elevator;

import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.cs.CaseType;
import com.example.robotdelivery.cs.CsService;
import com.example.robotdelivery.cs.Responsibility;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.notification.NotificationService;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 电梯系统协同：机器人进入楼栋后请求电梯选择楼层；
 * 电梯满载时自动重新排队（路线调整）。
 * 机器人连续三次请求电梯均因满载失败时，触发满载等待策略决策
 * （{@link ElevatorWaitStrategyService}）：继续等待 / 人工接力 / 通知用户下楼取件。
 */
@Service
public class ElevatorService {

    private final ElevatorRequestRepository elevatorRepository;
    private final DeliveryOrderRepository orderRepository;
    private final DeliveryEventService eventService;
    private final ElevatorWaitStrategyService strategyService;
    private final NotificationService notificationService;
    private final CsService csService;
    private final DeliveryProperties properties;

    public ElevatorService(ElevatorRequestRepository elevatorRepository,
                           DeliveryOrderRepository orderRepository,
                           DeliveryEventService eventService,
                           ElevatorWaitStrategyService strategyService,
                           NotificationService notificationService,
                           CsService csService,
                           DeliveryProperties properties) {
        this.elevatorRepository = elevatorRepository;
        this.orderRepository = orderRepository;
        this.eventService = eventService;
        this.strategyService = strategyService;
        this.notificationService = notificationService;
        this.csService = csService;
        this.properties = properties;
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
     * 连续满载次数未达阈值：机器人等待下一趟（自动重新请求，记为路线调整）。
     * 达到阈值（默认 3 次）：按订单时效、电量、楼层高度决策等待策略，不再无条件重试。
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
        int fullCount = order.getElevatorFullCount() + 1;
        order.setElevatorFullCount(fullCount);
        orderRepository.save(order);

        Map<String, Object> fullPayload = payload(order, request);
        fullPayload.put("consecutiveFullCount", fullCount);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_FULL, EventActor.ELEVATOR, fullPayload);

        if (fullCount < properties.getElevatorFullThreshold()) {
            return retryNextElevator(order, request);
        }
        return applyWaitStrategy(order, request, fullCount);
    }

    /** 连续满载未达阈值：路线调整，等待下一趟电梯并自动重新请求 */
    private ElevatorRequest retryNextElevator(DeliveryOrder order, ElevatorRequest request) {
        Map<String, Object> adjustPayload = payload(order, request);
        adjustPayload.put("detail", "电梯满载，机器人等待下一趟并重新请求");
        adjustPayload.put("consecutiveFullCount", order.getElevatorFullCount());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ROUTE_ADJUSTED, EventActor.SYSTEM, "ELEVATOR_FULL", null, adjustPayload);

        ElevatorRequest retry = createRequest(order, request.getFromFloor(), request.getToFloor());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_REQUESTED, EventActor.ROBOT, payload(order, retry));
        return retry;
    }

    /** 连续三次满载：依据时效/电量/楼层高度判定继续等待、人工接力或用户下楼取件 */
    private ElevatorRequest applyWaitStrategy(DeliveryOrder order, ElevatorRequest request, int fullCount) {
        ElevatorWaitStrategyService.Decision decision =
                strategyService.decide(order, request.getFromFloor(), request.getToFloor());
        LocalDateTime now = LocalDateTime.now();
        order.setWaitStrategy(decision.strategy());
        order.setWaitStrategyReason(decision.reason());
        order.setWaitStrategyDecidedAt(now);

        Map<String, Object> strategyPayload = payload(order, request);
        strategyPayload.put("consecutiveFullCount", fullCount);
        strategyPayload.put("strategy", decision.strategy().name());
        strategyPayload.put("factors", decision.factors());
        strategyPayload.put("detail", decision.reason());

        switch (decision.strategy()) {
            case WAIT_ELEVATOR -> {
                orderRepository.save(order);
                eventService.emit(order.getId(), order.getCommunity().getId(),
                        EventType.ELEVATOR_WAIT_STRATEGY, EventActor.SYSTEM, strategyPayload);
                eventService.emit(order.getId(), order.getCommunity().getId(),
                        EventType.ROUTE_ADJUSTED, EventActor.SYSTEM, "ELEVATOR_FULL_WAIT", null, strategyPayload);
                ElevatorRequest retry = createRequest(order, request.getFromFloor(), request.getToFloor());
                Map<String, Object> retryPayload = payload(order, retry);
                retryPayload.put("afterStrategy", "WAIT_ELEVATOR");
                eventService.emit(order.getId(), order.getCommunity().getId(),
                        EventType.ELEVATOR_REQUESTED, EventActor.ROBOT, retryPayload);
                csService.openCase(order.getId(), CaseType.ELEVATOR_FULL, Responsibility.ELEVATOR_SYSTEM,
                        "电梯连续" + fullCount + "次满载，机器人继续等待下一趟（楼层高/时效充裕）", null);
                return retry;
            }
            case MANUAL_RELAY -> {
                // 不再重新请求电梯；订单保持 ELEVATOR_WAIT 等待运营派单接力人员（/api/relays）
                orderRepository.save(order);
                strategyPayload.put("nextAction", "等待接力派单 POST /api/relays (登记接力人员身份)");
                eventService.emit(order.getId(), order.getCommunity().getId(),
                        EventType.ELEVATOR_WAIT_STRATEGY, EventActor.SYSTEM, strategyPayload);
                csService.openCase(order.getId(), CaseType.ELEVATOR_FULL, Responsibility.ELEVATOR_SYSTEM,
                        "电梯连续" + fullCount + "次满载，已决策人工接力，等待派单：" + decision.reason(), null);
                return request;
            }
            case USER_PICKUP_DOWNSTAIRS -> {
                order.setStatus(DeliveryStatus.DOWNSTAIRS_PICKUP);
                order.setDownstairsNotifiedAt(now);
                orderRepository.save(order);
                strategyPayload.put("nextAction", "等待用户下楼到大堂取件");
                eventService.emit(order.getId(), order.getCommunity().getId(),
                        EventType.ELEVATOR_WAIT_STRATEGY, EventActor.SYSTEM, strategyPayload);
                notificationService.notifyDownstairsPickup(order, fullCount);
                Map<String, Object> notifyPayload = new HashMap<>();
                notifyPayload.put("orderNo", order.getOrderNo());
                notifyPayload.put("channels", "SMS,MINI_PROGRAM");
                notifyPayload.put("userPhone", order.getUserPhone());
                notifyPayload.put("consecutiveFullCount", fullCount);
                eventService.emit(order.getId(), order.getCommunity().getId(),
                        EventType.USER_DOWNSTAIRS_NOTIFIED, EventActor.SYSTEM, notifyPayload);
                csService.openCase(order.getId(), CaseType.ELEVATOR_FULL, Responsibility.ELEVATOR_SYSTEM,
                        "电梯连续" + fullCount + "次满载，已通知用户下楼取件：" + decision.reason(), null);
                return request;
            }
            default -> throw new IllegalStateException("未知等待策略: " + decision.strategy());
        }
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
        order.setElevatorFullCount(0);
        order.setWaitStrategy(null);
        order.setWaitStrategyReason(null);
        order.setWaitStrategyDecidedAt(null);
        orderRepository.save(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_COMPLETED, EventActor.ELEVATOR, payload(order, request));
        return request;
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
        payload.put("consecutiveFullCount", order.getElevatorFullCount());
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
}
