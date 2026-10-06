package com.example.robotdelivery.relay;

/**
 * 人工接力交接单状态：
 * 已派单 -> 仓门开启（机器人在大堂向接力员开仓）-> 已接货 -> 用户已签收。
 */
public enum RelayStatus {
    DISPATCHED,          // 已派单，接力人员身份已登记
    COMPARTMENT_OPENED,  // 机器人载物仓已为接力人员开启
    HANDED_OVER,         // 接力人员已接货（接货时间）
    SIGNED               // 用户已签收（终态）
}
