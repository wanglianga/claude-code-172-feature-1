package com.example.robotdelivery.order;

import java.util.EnumSet;
import java.util.Set;

public enum DeliveryStatus {
    CREATED,            // 已创建
    ASSIGNED,           // 已分配机器人
    ACCESS_PENDING,     // 已到小区门口，等待物业门禁
    ENTERING,           // 门禁已开通，进入楼栋
    ELEVATOR_WAIT,      // 等待电梯
    ON_FLOOR,           // 已到达目标楼层
    WAITING_PICKUP,     // 已到门口，等待用户取件
    WAITING_DOWNSTAIRS, // 电梯连续满载：已通知用户下楼到大堂取件
    RELAYING,           // 人工接力进行中（配送员接手配送）
    COMPARTMENT_OPEN,   // 载物仓已打开
    SIGNED,             // 已签收（终态）
    RETURNING,          // 退回中
    RETURNED,           // 已退回（终态）
    EXCEPTION,          // 异常待处理（门禁拒绝/仓门故障等）
    CANCELLED;          // 已取消（终态）

    /** 仍处于配送途中（机器人被占用）的状态 */
    public static final Set<DeliveryStatus> ACTIVE = EnumSet.of(
            ASSIGNED, ACCESS_PENDING, ENTERING, ELEVATOR_WAIT, ON_FLOOR,
            WAITING_PICKUP, WAITING_DOWNSTAIRS, RELAYING, COMPARTMENT_OPEN,
            RETURNING, EXCEPTION);

    /** 终态 */
    public static final Set<DeliveryStatus> TERMINAL = EnumSet.of(SIGNED, RETURNED, CANCELLED);
}
