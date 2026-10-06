package com.example.robotdelivery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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

    /** 电梯满载连续失败多少次后触发等待策略决策 */
    private int elevatorFullThreshold = 3;

    /** 距预计送达时间剩余秒数低于该值视为时效紧急（默认 15 分钟） */
    private long urgentAheadSeconds = 900;

    /** 等待/乘梯所需电量余量阈值（百分比）：当前电量低于该值不宜继续等待电梯 */
    private int elevatorWaitBatteryThreshold = 40;

    /** 目标楼层高度阈值：高于等于该楼层（相对起始层的楼层差）视为高楼层 */
    private int highFloorThreshold = 12;

    /** 用户下楼取件等待时限秒数（超时进入退回流程） */
    private long downstairsPickupTimeoutSeconds = 300;

    /** 机器人配送基础配送费（元） */
    private BigDecimal baseDeliveryFee = new BigDecimal("5.00");

    /** 人工接力固定服务费（元） */
    private BigDecimal relayServiceFee = new BigDecimal("8.00");

    /** 人工接力每层楼加收费用（元/层，按目标楼层差计） */
    private BigDecimal relayFloorFee = new BigDecimal("0.50");
}
