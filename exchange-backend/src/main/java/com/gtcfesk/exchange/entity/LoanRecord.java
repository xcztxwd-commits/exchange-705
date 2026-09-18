package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "loan_record")
public class LoanRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "real_name", length = 100)
    private String realName; // 真实姓名

    @Column(name = "id_number", length = 50)
    private String idNumber; // 身份证号

    @Column(name = "phone", length = 32)
    private String phone; // 电话

    @Column(name = "address", length = 500)
    private String address; // 家庭住址

    @Column(nullable = false, precision = 32, scale = 16)
    private BigDecimal amount; // 贷款金额

    @Column(nullable = false)
    private Integer days; // 贷款期限（天数）

    @Column(name = "daily_rate", nullable = false, precision = 8, scale = 6)
    private BigDecimal dailyRate; // 日利率

    @Column(name = "free_days", nullable = false)
    private Integer freeDays = 0; // 免息天数

    @Column(name = "total_interest", nullable = false, precision = 32, scale = 16)
    private BigDecimal totalInterest; // 总利息

    @Column(name = "overdue_rate", precision = 8, scale = 6)
    private BigDecimal overdueRate; // 逾期费率

    @Column(name = "overdue_fee", precision = 32, scale = 16)
    private BigDecimal overdueFee = BigDecimal.ZERO; // 违约金/逾期费

    @Column(name = "repayment_amount", nullable = false, precision = 32, scale = 16)
    private BigDecimal repaymentAmount; // 需还款金额（本金+利息）

    @Column(nullable = false, length = 20)
    private String status = "PENDING"; // PENDING 待审核, APPROVED 已批准, SIGNED 已签约, COMPLETED 已完成, OVERDUE 已逾期, REJECTED 已拒绝

    @Column(name = "contract_signed", nullable = false)
    private Boolean contractSigned = false; // 合同是否已签署

    @Column(name = "signature_image", length = 500)
    private String signatureImage; // 签名图片URL

    @Column(name = "approved_at")
    private LocalDateTime approvedAt; // 批准时间

    @Column(name = "repayment_date")
    private LocalDateTime repaymentDate; // 还款日期（到期日）

    @Column(name = "actual_repayment_at")
    private LocalDateTime actualRepaymentAt; // 实际还款时间

    @Column(length = 500)
    private String remark; // 备注

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ==== 仅用于接口展示的临时字段（不入库） ====
    @Transient
    private String agentInfo; // 代理信息，格式：所属代理:用户名

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

