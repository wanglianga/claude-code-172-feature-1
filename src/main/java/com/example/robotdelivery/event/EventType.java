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
    ELEVATOR_WAIT_STRATEGY, // 连续三次满载后的等待策略决策（继续等待/人工接力/用户下楼取件）
    OBSTACLE_WAIT,          // 避障停留（老人儿童挡路等）
    ROUTE_ADJUSTED,         // 路线调整（施工绕行/电梯满载/送错楼栋等）
    LOW_BATTERY,            // 机器人电量不足
    ARRIVED_AT_DOOR,        // 到达用户门口
    USER_NOTIFIED,          // 短信/小程序通知用户取件
    USER_DOWNSTAIRS_NOTIFIED, // 通知用户下楼取件（电梯连续满载）
    COMPARTMENT_OPENED,     // 载物仓打开
    COMPARTMENT_OPEN_FAILED,// 载物仓打不开
    RELAY_COMPARTMENT_OPENED, // 人工接力：机器人为接力人员打开载物仓
    SIGNED,                 // 用户签收
    RELAY_SIGNED,           // 人工接力完成：接力人员送达，用户签收
    DOWNSTAIRS_SIGNED,      // 用户下楼取件签收
    PICKUP_TIMEOUT,         // 用户超时不取
    COLDCHAIN_TIMEOUT,      // 冷藏订单超时
    RETURN_STARTED,         // 开始退回
    RETURNED,               // 退回完成
    MANUAL_RELAY,           // 人工接力
    RELAY_DISPATCHED,       // 接力派单（记录接力人员身份）
    RELAY_HANDOVER,         // 接力接货（载物仓交接完成，记录接货时间）
    RELAY_FEE_RECALCULATED, // 人工接力费用重算
    DAMAGE_REPORTED,        // 商品损坏上报（分阶段责任判定）
    COMPLAINT_FILED,        // 用户投诉（如送错楼栋）
    CS_INTERVENTION,        // 客服介入
    CS_CASE_CLOSED,         // 客服工单关闭
    DELIVERY_CANCELLED,     // 配送取消
    RULE_UPDATED            // 物业规则变更（社区级事件，deliveryId 为空）
}
