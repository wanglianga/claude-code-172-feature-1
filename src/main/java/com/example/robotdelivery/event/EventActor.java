package com.example.robotdelivery.event;

/**
 * 事件行为方：机器人 / 物业 / 电梯系统 / 用户 / 运营 / 客服 / 系统。
 */
public enum EventActor {
    SYSTEM,
    ROBOT,
    PROPERTY,
    ELEVATOR,
    USER,
    COURIER,   // 人工接力配送员
    OPS,
    CS
}
