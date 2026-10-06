package com.example.robotdelivery.order;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.BuildingRepository;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.community.CommunityRepository;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.relay.FeeCalculator;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    private final DeliveryOrderRepository orderRepository;
    private final CommunityRepository communityRepository;
    private final BuildingRepository buildingRepository;
    private final DeliveryEventService eventService;
    private final DeliveryProperties properties;
    private final FeeCalculator feeCalculator;

    public OrderService(DeliveryOrderRepository orderRepository,
                        CommunityRepository communityRepository,
                        BuildingRepository buildingRepository,
                        DeliveryEventService eventService,
                        DeliveryProperties properties,
                        FeeCalculator feeCalculator) {
        this.orderRepository = orderRepository;
        this.communityRepository = communityRepository;
        this.buildingRepository = buildingRepository;
        this.eventService = eventService;
        this.properties = properties;
        this.feeCalculator = feeCalculator;
    }

    @Transactional
    public DeliveryOrder create(OrderType orderType, Long communityId, Long buildingId,
                                String unitNo, String roomNo, boolean coldChain,
                                LocalDateTime estimatedDeliveryAt, String userName, String userPhone) {
        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> ApiException.notFound("社区不存在: " + communityId));
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> ApiException.notFound("楼栋不存在: " + buildingId));
        if (!building.getCommunity().getId().equals(community.getId())) {
            throw ApiException.badRequest("楼栋不属于该社区: buildingId=" + buildingId);
        }

        DeliveryOrder order = new DeliveryOrder();
        order.setOrderNo("D" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        order.setOrderType(orderType);
        order.setCommunity(community);
        order.setBuilding(building);
        order.setUnitNo(unitNo);
        order.setRoomNo(roomNo);
        order.setColdChain(coldChain);
        order.setEstimatedDeliveryAt(estimatedDeliveryAt);
        order.setUserName(userName);
        order.setUserPhone(userPhone);
        order.setStatus(DeliveryStatus.CREATED);
        if (coldChain) {
            order.setColdChainDeadline(LocalDateTime.now().plusSeconds(properties.getColdchainTimeoutSeconds()));
        }
        FeeCalculator.FeeResult baseFee = feeCalculator.baseFee(order);
        order.setDeliveryFee(baseFee.total());
        order.setDeliveryFeeDetail(baseFee.detail());
        orderRepository.save(order);

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("orderType", orderType.name());
        payload.put("coldChain", coldChain);
        payload.put("community", community.getName());
        payload.put("building", building.getName());
        payload.put("unitNo", unitNo);
        payload.put("roomNo", roomNo);
        payload.put("estimatedDeliveryAt", estimatedDeliveryAt == null ? null : estimatedDeliveryAt.toString());
        payload.put("coldChainDeadline",
                order.getColdChainDeadline() == null ? null : order.getColdChainDeadline().toString());
        payload.put("userName", userName);
        payload.put("userPhone", userPhone);
        eventService.emit(order.getId(), community.getId(), EventType.ORDER_CREATED, EventActor.OPS, payload);
        return order;
    }

    public List<DeliveryOrder> query(DeliveryStatus status, Long communityId, Long buildingId, OrderType orderType) {
        Specification<DeliveryOrder> spec = (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (communityId != null) {
                predicates.add(cb.equal(root.get("community").get("id"), communityId));
            }
            if (buildingId != null) {
                predicates.add(cb.equal(root.get("building").get("id"), buildingId));
            }
            if (orderType != null) {
                predicates.add(cb.equal(root.get("orderType"), orderType));
            }
            q.orderBy(cb.desc(root.get("id")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return orderRepository.findAll(spec);
    }

    public DeliveryOrder mustGet(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + id));
    }
}
