package com.example.robotdelivery.relay;

/**
 * 人工接力触发原因。
 */
public enum RelayTriggerReason {
    ELEVATOR_FULL,  // 电梯连续满载，策略判定改人工接力
    LOW_BATTERY,    // 机器人电量不足
    ROBOT_FAULT,    // 机器人故障（如载物仓打不开）
    MANUAL          // 运营/客服人工发起
}
