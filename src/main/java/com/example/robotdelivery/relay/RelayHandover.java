package com.example.robotdelivery.relay;

import com.example.robotdelivery.order.DeliveryOrder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 人工接力交接记录：机器人无法继续乘梯/配送时，配送员现场开启载物仓接货并完成最后配送。
 * 完整记录接力人员身份、接货时间、仓门开启时间与用户签收，用于责任追溯。
 */
@Getter
@Setter
@Entity
@Table(name = "relay_handover")
public class RelayHandover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private DeliveryOrder delivery;

    /** 接力人员身份：工号 */
    @Column(name = "courier_id", nullable = false, length = 64)
    private String courierId;

    /** 接力人员身份：姓名 */
    @Column(name = "courier_name", nullable = false, length = 64)
    private String courierName;

    /** 接力人员身份：联系电话 */
    @Column(name = "courier_phone", length = 32)
    private String courierPhone;

    /** 接货地点（机器人等待位置，如 1栋1层大堂） */
    @Column(name = "handover_location", nullable = false, length = 128)
    private String handoverLocation;

    /** 仓门开启时间（机器人载物仓在交接现场开启） */
    @Column(name = "compartment_opened_at")
    private LocalDateTime compartmentOpenedAt;

    /** 接货时间（配送员从载物仓取走商品） */
    @Column(name = "handed_at")
    private LocalDateTime handedAt;

    /** 配送员送达用户门口时间 */
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    /** 交接的载物仓编号 */
    @Column(name = "compartment_no", length = 10)
    private String compartmentNo;

    /** 用户签收人 */
    @Column(name = "sign_by", length = 64)
    private String signBy;

    /** 用户签收时间 */
    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RelayStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_reason", nullable = false, length = 40)
    private RelayTriggerReason triggerReason;

    /** 费用重算（分）：原机器人配送费 */
    @Column(name = "original_fee_cents", nullable = false)
    private int originalFeeCents;

    /** 费用重算（分）：接力配送费 */
    @Column(name = "relay_fee_cents", nullable = false)
    private int relayFeeCents;

    /** 费用重算（分）：合计 */
    @Column(name = "total_fee_cents", nullable = false)
    private int totalFeeCents;

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
