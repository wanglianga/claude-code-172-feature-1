package com.example.robotdelivery.cs;

public enum CaseType {
    ACCESS_DENIED,       // 物业门禁拒绝
    NIGHT_RESTRICTED,    // 夜间限制运行
    LOW_BATTERY,         // 机器人电量不足
    COMPARTMENT_FAULT,   // 载物仓打不开
    PICKUP_TIMEOUT,      // 用户超时不取
    COLDCHAIN_TIMEOUT,   // 冷藏订单超时
    ELEVATOR_TIMEOUT,    // 电梯请求超时
    ELEVATOR_FULL,       // 电梯连续满载触发等待策略
    RELAY_DAMAGE,        // 人工接力阶段商品损坏
    RELAY_DELAY,         // 人工接力阶段延迟
    PICKUP_DAMAGE,       // 用户取件阶段商品损坏
    COMPLAINT,           // 用户投诉（送错楼栋等）
    OTHER
}
