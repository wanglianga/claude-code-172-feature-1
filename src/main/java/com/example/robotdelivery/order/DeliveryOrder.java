package com.example.robotdelivery.order;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.elevator.ElevatorWaitStrategy;
import com.example.robotdelivery.robot.Robot;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "delivery_order")
public class DeliveryOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 40)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    private OrderType orderType;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    @Column(name = "unit_no", length = 20)
    private String unitNo;

    @Column(name = "room_no", length = 20)
    private String roomNo;

    /** 是否需要冷藏 */
    @Column(name = "cold_chain", nullable = false)
    private boolean coldChain;

    /** 预计送达时间 */
    @Column(name = "estimated_delivery_at")
    private LocalDateTime estimatedDeliveryAt;

    @Column(name = "user_name", nullable = false, length = 64)
    private String userName;

    /** 用户联系方式（手机号） */
    @Column(name = "user_phone", nullable = false, length = 32)
    private String userPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DeliveryStatus status;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "robot_id")
    private Robot robot;

    /** 载物仓编号 */
    @Column(name = "compartment_no", length = 10)
    private String compartmentNo;

    /** 人工接力配送员 */
    @Column(name = "relay_courier", length = 64)
    private String relayCourier;

    /** 电梯连续满载次数（每次上报满载 +1，派梯/乘梯成功后清零） */
    @Column(name = "elevator_full_count", nullable = false)
    private int elevatorFullCount;

    /** 三次满载后的等待策略：继续等待/人工接力/通知用户下楼取件 */
    @Enumerated(EnumType.STRING)
    @Column(name = "wait_strategy", length = 28)
    private ElevatorWaitStrategy waitStrategy;

    @Column(name = "wait_strategy_reason", length = 255)
    private String waitStrategyReason;

    @Column(name = "wait_strategy_decided_at")
    private LocalDateTime waitStrategyDecidedAt;

    /** 重算后的配送费用（人工接力时追加接力费） */
    @Column(name = "delivery_fee", precision = 10, scale = 2)
    private BigDecimal deliveryFee;

    /** 费用构成明细（JSON 文本，含基础配送费/接力费/减免） */
    @Column(name = "delivery_fee_detail", columnDefinition = "text")
    private String deliveryFeeDetail;

    /** 人工接力开始时间 */
    @Column(name = "relay_started_at")
    private LocalDateTime relayStartedAt;

    /** 接力人员从机器人接货时间（载物仓交接完成） */
    @Column(name = "handover_at")
    private LocalDateTime handoverAt;

    /** 通知用户下楼取件时间 */
    @Column(name = "downstairs_notified_at")
    private LocalDateTime downstairsNotifiedAt;

    /** 用户楼下取件签收时间 */
    @Column(name = "downstairs_pickup_at")
    private LocalDateTime downstairsPickupAt;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "return_reason", length = 255)
    private String returnReason;

    /** 冷藏订单履约截止时间 */
    @Column(name = "cold_chain_deadline")
    private LocalDateTime coldChainDeadline;

    @Column(name = "cold_chain_timed_out", nullable = false)
    private boolean coldChainTimedOut;

    @Column(name = "pickup_timed_out", nullable = false)
    private boolean pickupTimedOut;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
