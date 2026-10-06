package com.example.robotdelivery.elevator;

/**
 * 电梯连续满载后的等待策略（服务依据订单时效、电量、楼层高度判定）。
 */
public enum ElevatorWaitStrategy {
    WAIT_ELEVATOR,            // 继续等待：机器人等候下一趟电梯并自动重新请求
    MANUAL_RELAY,             // 人工接力：配送员到楼内接力，订单状态/载物仓交接/费用重算
    USER_PICKUP_DOWNSTAIRS    // 通知用户下楼取件：用户到楼栋大堂/电梯厅从机器人取件
}
