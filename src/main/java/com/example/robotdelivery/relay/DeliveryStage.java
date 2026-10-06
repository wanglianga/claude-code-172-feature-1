package com.example.robotdelivery.relay;

import com.example.robotdelivery.cs.Responsibility;

/**
 * 电梯满载异常后的三个配送阶段，商品损坏或延迟按发生阶段判定责任：
 * 1. 机器人等待阶段（连续满载候梯，商品仍在机器人载物仓）
 * 2. 人工接力阶段（接力人员已接货，商品在接力人员 custody 中）
 * 3. 用户取件阶段（用户下楼/在门口从机器人取件）
 */
public enum DeliveryStage {

    ROBOT_WAITING("机器人等待阶段",
            Responsibility.ROBOT_VENDOR, Responsibility.ELEVATOR_SYSTEM),
    MANUAL_RELAY("人工接力阶段",
            Responsibility.RELAY_COURIER, Responsibility.RELAY_COURIER),
    USER_PICKUP("用户取件阶段",
            Responsibility.USER, Responsibility.USER);

    private final String label;
    /** 该阶段发生商品损坏的责任方 */
    private final Responsibility damageResponsibility;
    /** 该阶段发生配送延迟的责任方 */
    private final Responsibility delayResponsibility;

    DeliveryStage(String label, Responsibility damageResponsibility, Responsibility delayResponsibility) {
        this.label = label;
        this.damageResponsibility = damageResponsibility;
        this.delayResponsibility = delayResponsibility;
    }

    public String label() {
        return label;
    }

    public Responsibility damageResponsibility() {
        return damageResponsibility;
    }

    public Responsibility delayResponsibility() {
        return delayResponsibility;
    }
}
