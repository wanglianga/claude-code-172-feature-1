package com.example.robotdelivery.elevator;

import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.OrderType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 电梯满载等待策略决策：机器人连续三次请求电梯因满载失败后，
 * 依据订单时效（距预计送达剩余时间）、机器人电量、目标楼层高度三个因素，
 * 判定继续等待 / 人工接力 / 通知用户下楼取件。
 *
 * 判定规则（确定性优先级）：
 * 1. 电量低于乘梯等待余量阈值  -> 人工接力（机器人不宜继续滞留候梯）
 * 2. 时效紧急且高楼层          -> 通知用户下楼取件（上下楼耗时不可控，用户下来一趟最快）
 * 3. 时效紧急且楼层不高        -> 人工接力（接力员走楼梯可在时效内送达）
 * 4. 时效不紧急但楼层较低      -> 人工接力（步行成本低，避免持续占用电梯口）
 * 5. 其余（时效充裕/电量充足/高楼层）-> 继续等待下一趟电梯
 */
@Component
public class ElevatorWaitStrategyService {

    private final DeliveryProperties properties;

    public ElevatorWaitStrategyService(DeliveryProperties properties) {
        this.properties = properties;
    }

    public Decision decide(DeliveryOrder order, Integer fromFloor, Integer toFloor) {
        int battery = order.getRobot() == null || order.getRobot().getBattery() == null
                ? 100 : order.getRobot().getBattery();
        int floorDelta = Math.abs(toFloor - fromFloor);
        boolean batteryLow = battery < properties.getElevatorWaitBatteryThreshold();
        boolean highFloor = floorDelta >= properties.getHighFloorThreshold();
        boolean urgent = isUrgent(order);

        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("fullCount", order.getElevatorFullCount());
        factors.put("fromFloor", fromFloor);
        factors.put("toFloor", toFloor);
        factors.put("floorDelta", floorDelta);
        factors.put("highFloor", highFloor);
        factors.put("battery", battery);
        factors.put("batteryLow", batteryLow);
        factors.put("urgent", urgent);
        factors.put("estimatedDeliveryAt",
                order.getEstimatedDeliveryAt() == null ? null : order.getEstimatedDeliveryAt().toString());
        factors.put("orderType", order.getOrderType().name());

        ElevatorWaitStrategy strategy;
        String reason;
        if (batteryLow) {
            strategy = ElevatorWaitStrategy.MANUAL_RELAY;
            reason = "机器人电量" + battery + "%低于候梯余量阈值"
                    + properties.getElevatorWaitBatteryThreshold() + "%，不宜继续等待电梯，改为人工接力";
        } else if (urgent && highFloor) {
            strategy = ElevatorWaitStrategy.USER_PICKUP_DOWNSTAIRS;
            reason = "订单时效紧急且目标楼层差" + floorDelta + "层（高楼层），继续等梯或接力上楼均不可控，"
                    + "通知用户下楼到大堂取件最快";
        } else if (urgent) {
            strategy = ElevatorWaitStrategy.MANUAL_RELAY;
            reason = "订单时效紧急且目标楼层差仅" + floorDelta + "层，接力人员步行上楼可在时效内送达，改为人工接力";
        } else if (!highFloor) {
            strategy = ElevatorWaitStrategy.MANUAL_RELAY;
            reason = "时效不紧急但目标楼层差仅" + floorDelta + "层，人工步行接力成本低，"
                    + "避免机器人持续占用电梯口，改为人工接力";
        } else {
            strategy = ElevatorWaitStrategy.WAIT_ELEVATOR;
            reason = "时效充裕、电量" + battery + "%充足、目标楼层差" + floorDelta + "层较高，"
                    + "机器人继续等待下一趟电梯";
        }
        factors.put("strategy", strategy.name());
        factors.put("reason", reason);
        return new Decision(strategy, reason, factors);
    }

    /**
     * 时效紧急：有预计送达时间且剩余时间小于紧急阈值；
     * 未填写预计送达时间时，外卖/药品默认时效敏感。
     */
    private boolean isUrgent(DeliveryOrder order) {
        LocalDateTime eta = order.getEstimatedDeliveryAt();
        if (eta != null) {
            long secondsLeft = Duration.between(LocalDateTime.now(), eta).getSeconds();
            return secondsLeft <= properties.getUrgentAheadSeconds();
        }
        return order.getOrderType() == OrderType.FOOD || order.getOrderType() == OrderType.MEDICINE;
    }

    public record Decision(ElevatorWaitStrategy strategy, String reason, Map<String, Object> factors) {
    }
}
