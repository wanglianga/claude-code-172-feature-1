package com.example.robotdelivery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "delivery")
public class DeliveryProperties {

    /** 用户超时不取件秒数 */
    private long pickupTimeoutSeconds = 600;

    /** 冷藏订单履约时限秒数 */
    private long coldchainTimeoutSeconds = 2700;

    /** 电梯请求超时秒数 */
    private long elevatorTimeoutSeconds = 180;

    /** 低电量阈值（百分比） */
    private int lowBatteryThreshold = 20;

    /** 超时巡检间隔毫秒 */
    private long schedulerIntervalMs = 10000;

    /** 是否开启超时巡检 */
    private boolean schedulerEnabled = true;

    /** 连续电梯满载触发策略判定的次数 */
    private int elevatorFullThreshold = 3;

    /** 时效紧迫判定：距预计送达/冷藏截止不足该秒数视为紧迫（默认10分钟） */
    private long urgentWindowSeconds = 600;

    /** 高楼层判定：目标楼层不低于该值视为高楼（默认10层） */
    private int highFloorThreshold = 10;

    /** 机器人基础配送费（分，默认5元） */
    private int baseFeeCents = 500;

    /** 每层楼加价（分，默认3角/层） */
    private int perFloorFeeCents = 30;

    /** 冷藏单附加费（分，默认2元） */
    private int coldChainFeeCents = 200;

    /** 人工接力附加费（分，默认3元）；因机器人/电梯/平台侧原因触发时免收 */
    private int relaySurchargeCents = 300;

    /** 用户下楼取件超时秒数（默认与取件超时一致由调用方取值，此处默认600） */
    private long downstairsTimeoutSeconds = 600;

    /** 人工接力配送时效（接货到送达，默认5分钟），超时判定为接力阶段延迟 */
    private long relaySlaSeconds = 300;
}
