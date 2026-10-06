package com.example.robotdelivery.relay;

import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.order.DeliveryOrder;
import org.springframework.stereotype.Component;

/**
 * 配送费用计算（单位：分）。
 * 机器人配送费 = 基础费 + 楼层加价 × 乘梯楼层数 + 冷藏附加费；
 * 人工接力时重新计算：因电梯满载/低电量/设备故障等机器人或平台侧原因触发的，
 * 接力附加费由平台承担、免收用户；仅运营为用户便利主动发起的接力收取接力附加费。
 */
@Component
public class FeeService {

    private final DeliveryProperties properties;

    public FeeService(DeliveryProperties properties) {
        this.properties = properties;
    }

    /** 从房号推断楼层：901→9，1801→18，无法识别按1层处理 */
    public static int floorFromRoomNo(String roomNo) {
        if (roomNo == null) {
            return 1;
        }
        String digits = roomNo.replaceAll("^\\D*(\\d+).*$", "$1");
        if (digits.isEmpty()) {
            return 1;
        }
        int n = Integer.parseInt(digits);
        return digits.length() >= 3 ? n / 100 : n;
    }

    /** 机器人配送费（分） */
    public int robotFee(DeliveryOrder order, Integer toFloor) {
        int floor = toFloor != null ? toFloor : floorFromRoomNo(order.getRoomNo());
        int floors = Math.max(0, floor - 1);
        int fee = properties.getBaseFeeCents() + properties.getPerFloorFeeCents() * floors;
        if (order.isColdChain()) {
            fee += properties.getColdChainFeeCents();
        }
        return fee;
    }

    public record FeeBreakdown(int robotFeeCents, int relaySurchargeCents, int totalFeeCents,
                               boolean surchargeWaived, String detail) {
    }

    /**
     * 人工接力费用重算。
     *
     * @param order          配送单
     * @param floorsClimbed  机器人已/需乘梯楼层数
     * @param reason         接力触发原因
     */
    public FeeBreakdown recalcForRelay(DeliveryOrder order, int floorsClimbed, RelayTriggerReason reason) {
        int robotFee = properties.getBaseFeeCents()
                + properties.getPerFloorFeeCents() * Math.max(0, floorsClimbed)
                + (order.isColdChain() ? properties.getColdChainFeeCents() : 0);
        // 平台侧原因（电梯满载、电量不足、设备故障）触发接力，接力附加费免收用户
        boolean waived = reason != RelayTriggerReason.MANUAL;
        int surcharge = waived ? 0 : properties.getRelaySurchargeCents();
        int total = robotFee + surcharge;

        StringBuilder detail = new StringBuilder();
        detail.append("基础配送费").append(yuan(properties.getBaseFeeCents()));
        if (floorsClimbed > 0) {
            detail.append("+楼层加价").append(yuan(properties.getPerFloorFeeCents()))
                    .append("×").append(floorsClimbed).append("层");
        }
        if (order.isColdChain()) {
            detail.append("+冷藏附加费").append(yuan(properties.getColdChainFeeCents()));
        }
        if (waived) {
            detail.append("；").append(reasonText(reason)).append("，人工接力附加费免收");
        } else {
            detail.append("+人工接力附加费").append(yuan(properties.getRelaySurchargeCents()));
        }
        return new FeeBreakdown(robotFee, surcharge, total, waived, detail.toString());
    }

    public static String yuan(int cents) {
        return String.format("¥%.2f", cents / 100.0);
    }

    private String reasonText(RelayTriggerReason reason) {
        return switch (reason) {
            case ELEVATOR_FULL -> "电梯连续满载";
            case LOW_BATTERY -> "机器人电量不足";
            case ROBOT_FAULT -> "机器人设备故障";
            case MANUAL -> "人工发起接力";
        };
    }
}
