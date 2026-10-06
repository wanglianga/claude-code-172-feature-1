package com.example.robotdelivery.cs;

/**
 * 异常责任归属，客服端据此追踪异常责任。
 * 电梯满载场景的商品损坏/延迟按三个配送阶段判定：
 * 机器人等待阶段 / 人工接力阶段 / 用户取件阶段。
 */
public enum Responsibility {
    NONE,            // 待定
    ROBOT_VENDOR,    // 机器人/设备方（含机器人电梯等待阶段）
    PROPERTY,        // 物业
    ELEVATOR_SYSTEM, // 电梯系统
    USER,            // 用户（含用户下楼取件阶段）
    PLATFORM,        // 平台运营
    THIRD_PARTY,     // 第三方（如施工方）
    RELAY_COURIER    // 人工接力配送员（人工接力阶段）
}
