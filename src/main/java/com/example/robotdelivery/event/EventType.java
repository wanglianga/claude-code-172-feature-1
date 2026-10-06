package com.example.robotdelivery.event;

/**
 * 统一配送事件类型：门禁开通、路线调整、电梯请求、避障停留、签收、退回、客服介入等
 * 全部写入同一配送事件流（Kafka topic: delivery-events -> delivery_event 表）。
 */
public enum EventType {
    ORDER_CREATED,          // 订单创建
    ROBOT_ASSIGNED,         // 分配机器人与载物仓
    ACCESS_REQUESTED,       // 机器人到小区门口，申请物业门禁
    ACCESS_GRANTED,         // 物业门禁开通
    ACCESS_DENIED,          // 物业门禁拒绝
    NIGHT_RESTRICTED,       // 物业夜间限制运行
    ELEVATOR_REQUESTED,     // 与电梯系统协同请求楼层
    ELEVATOR_ASSIGNED,      // 电梯系统派梯
    ELEVATOR_FULL,          // 电梯满载
    ELEVATOR_TIMEOUT,       // 电梯请求超时
    ELEVATOR_COMPLETED,     // 乘梯完成到达目标楼层
    OBSTACLE_WAIT,          // 避障停留（老人儿童挡路等）
    ROUTE_ADJUSTED,         // 路线调整（施工绕行/电梯满载/送错楼栋等）
    LOW_BATTERY,            // 机器人电量不足
    ARRIVED_AT_DOOR,        // 到达用户门口
    USER_NOTIFIED,          // 短信/小程序通知用户取件
    COMPARTMENT_OPENED,     // 载物仓打开
    COMPARTMENT_OPEN_FAILED,// 载物仓打不开
    SIGNED,                 // 用户签收
    PICKUP_TIMEOUT,         // 用户超时不取
    COLDCHAIN_TIMEOUT,      // 冷藏订单超时
    RETURN_STARTED,         // 开始退回
    RETURNED,               // 退回完成
    MANUAL_RELAY,           // 人工接力启动
    RELAY_COMPARTMENT_OPENED, // 人工接力：现场开启机器人载物仓交接
    RELAY_HANDOVER,         // 人工接力：配送员接货（记录人员身份与接货时间）
    RELAY_DELIVERED,        // 人工接力：配送员送达用户门口
    FEE_RECALCULATED,       // 人工接力配送费用重新计算
    ELEVATOR_FULL_STRATEGY, // 连续满载后的等待策略判定（继续等待/人工接力/用户下楼）
    LIABILITY_JUDGED,       // 三阶段（机器人等待/人工接力/用户取件）责任判定
    COMPLAINT_FILED,        // 用户投诉（如送错楼栋）
    CS_INTERVENTION,        // 客服介入
    CS_CASE_CLOSED,         // 客服工单关闭
    DELIVERY_CANCELLED,     // 配送取消
    RULE_UPDATED            // 物业规则变更（社区级事件，deliveryId 为空）
}
