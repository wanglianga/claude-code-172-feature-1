package com.example.robotdelivery.relay;

import com.example.robotdelivery.order.DeliveryOrder;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 人工接力交接单：电梯连续满载改人工接力时生成，
 * 完整记录接力人员身份、接货时间、仓门开启与用户签收，作为分阶段责任判定依据。
 */
@Getter
@Setter
@Entity
@Table(name = "relay_handover")
public class RelayHandover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "handover_no", nullable = false, unique = true, length = 40)
    private String handoverNo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private DeliveryOrder delivery;

    @Enumerated(EnumType.STRING)
    @Column(name = "handover_type", nullable = false, length = 24)
    private RelayType handoverType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RelayStatus status;

    /** 接力人员姓名 */
    @Column(name = "courier_name", nullable = false, length = 64)
    private String courierName;

    /** 接力人员手机号 */
    @Column(name = "courier_phone", length = 32)
    private String courierPhone;

    /** 所属配送公司/物业部门 */
    @Column(name = "courier_company", length = 64)
    private String courierCompany;

    /** 工号（身份核验） */
    @Column(name = "courier_employee_no", length = 40)
    private String courierEmployeeNo;

    /** 交接的载物仓编号 */
    @Column(name = "compartment_no", length = 10)
    private String compartmentNo;

    /** 派单时间（接力人员身份登记） */
    @Column(name = "dispatched_at", nullable = false)
    private LocalDateTime dispatchedAt;

    /** 接货时间（接力人员从机器人取走商品） */
    @Column(name = "handed_over_at")
    private LocalDateTime handedOverAt;

    /** 仓门开启时间（机器人为接力人员打开载物仓） */
    @Column(name = "compartment_opened_at")
    private LocalDateTime compartmentOpenedAt;

    /** 用户签收时间 */
    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    /** 签收人 */
    @Column(name = "sign_by", length = 64)
    private String signBy;

    /** 接力段是否上报商品损坏 */
    @Column(name = "damage_reported", nullable = false)
    private boolean damageReported;

    @Column(name = "damage_note", columnDefinition = "text")
    private String damageNote;

    /** 本次接力配送费用（重算后） */
    @Column(name = "fee", precision = 10, scale = 2)
    private BigDecimal fee;

    @Column(columnDefinition = "text")
    private String note;

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
