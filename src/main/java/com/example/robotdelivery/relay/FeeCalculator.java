package com.example.robotdelivery.relay;

import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.order.DeliveryOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配送费用计算：
 * 基础机器人配送费；选择人工接力时在基础费上重算，追加接力服务费与楼层费；
 * 经分阶段责任判定属于接力人员责任的商品损坏，可减免接力服务费作为补偿。
 */
@Component
public class FeeCalculator {

    private final DeliveryProperties properties;
    private final ObjectMapper objectMapper;

    public FeeCalculator(DeliveryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 下单时的基础配送费 */
    public FeeResult baseFee(DeliveryOrder order) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("baseDeliveryFee", properties.getBaseDeliveryFee());
        detail.put("relayServiceFee", BigDecimal.ZERO);
        detail.put("relayFloorFee", BigDecimal.ZERO);
        detail.put("floorDelta", 0);
        detail.put("deduction", BigDecimal.ZERO);
        detail.put("total", properties.getBaseDeliveryFee());
        detail.put("mode", "ROBOT");
        return new FeeResult(properties.getBaseDeliveryFee(), toJson(detail));
    }

    /** 人工接力重算费用：基础配送费 + 接力服务费 + 楼层费 */
    public FeeResult relayFee(DeliveryOrder order, int floorDelta) {
        return relayFee(order, floorDelta, false);
    }

    /**
     * @param waiveRelayServiceFee 接力人员责任导致商品损坏时，减免接力服务费
     */
    public FeeResult relayFee(DeliveryOrder order, int floorDelta, boolean waiveRelayServiceFee) {
        BigDecimal floorFee = properties.getRelayFloorFee().multiply(BigDecimal.valueOf(Math.max(floorDelta, 0)));
        BigDecimal serviceFee = waiveRelayServiceFee ? BigDecimal.ZERO : properties.getRelayServiceFee();
        BigDecimal deduction = waiveRelayServiceFee ? properties.getRelayServiceFee() : BigDecimal.ZERO;
        BigDecimal total = properties.getBaseDeliveryFee().add(serviceFee).add(floorFee);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("baseDeliveryFee", properties.getBaseDeliveryFee());
        detail.put("relayServiceFee", serviceFee);
        detail.put("relayFloorFee", floorFee);
        detail.put("floorDelta", floorDelta);
        detail.put("deduction", deduction);
        detail.put("total", total);
        detail.put("mode", "MANUAL_RELAY");
        detail.put("deductionReason", waiveRelayServiceFee ? "接力阶段商品损坏，减免人工接力服务费" : null);
        return new FeeResult(total, toJson(detail));
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return String.valueOf(detail);
        }
    }

    public record FeeResult(BigDecimal total, String detail) {
    }
}
