package com.example.robotdelivery.relay;

public enum RelayStatus {
    DISPATCHED,  // 已派发，等待配送员到机器人处接货
    HANDED_OVER, // 已现场开启载物仓接货
    DELIVERED,   // 配送员已送达用户门口
    SIGNED       // 用户已签收（终态）
}
