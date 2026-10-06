package com.example.robotdelivery.elevator;

public enum ElevatorStatus {
    PENDING,    // 等待电梯系统派梯
    ASSIGNED,   // 已派梯
    FULL,       // 电梯满载
    TIMEOUT,    // 请求超时
    CANCELLED,  // 策略改人工接力/用户下楼后，未完成的请求作废
    COMPLETED   // 乘梯完成
}
