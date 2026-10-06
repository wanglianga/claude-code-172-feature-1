package com.example.robotdelivery.liability;

/**
 * 三阶段责任判定中的配送阶段。
 * ROBOT_WAIT 机器人等待阶段（机器人在楼下持续等待电梯，含连续满载）；
 * RELAY      人工接力阶段（配送员接货到送达）；
 * USER_PICKUP 用户取件阶段（通知到门/下楼后，用户开仓取件）。
 */
public enum LiabilityStage {
    ROBOT_WAIT,
    RELAY,
    USER_PICKUP,
    NONE
}
