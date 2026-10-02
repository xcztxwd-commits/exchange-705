package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.OrderRequest;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.KycRecord;
import com.gtcfesk.exchange.entity.LoanRecord;
import com.gtcfesk.exchange.entity.LoanSetting;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.KycRecordRepository;
import com.gtcfesk.exchange.repository.LoanRecordRepository;
import com.gtcfesk.exchange.repository.LoanSettingRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LoanService {
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService audit;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;

    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationEnvironment simulation;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.admin.LoanReviewService simulationReview;
    private final LoanRecordRepository loanRecordRepository;
    private final LoanSettingRepository loanSettingRepository;
    private final UserAccountRepository userAccountRepository;
    private final KycRecordRepository kycRecordRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final LoanPersonalInfoService loanPersonalInfoService;

    public List<LoanSetting> getAvailableLoanSettings() {
        return loanSettingRepository.findByTenantIdAndEnabledTrueOrderByDaysAsc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
    }

    public LoanSetting getLoanSettingById(Long id) {
        return loanSettingRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id)
                .orElseThrow(() -> new BusinessException("贷款设置不存在"));
    }

    @Transactional
    public LoanRecord createLoan(Long userId, BigDecimal amount, Long settingId) {
        return createLoan(userId,amount,settingId,null);
    }

    @Transactional
    public LoanRecord createLoan(Long userId, BigDecimal amount, Long settingId, String key) {
        String requestKey=OrderRequest.optional(key);
        String requestHash=requestKey==null?null:OrderRequest.hash("loan",settingId,amount);
        tenantPolicy.requireNewBusiness("loan");
        com.gtcfesk.exchange.common.TradeValidation.positive(amount, "贷款金额");
        LoanSetting setting = loanSettingRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), settingId)
                .orElseThrow(() -> new BusinessException("贷款设置不存在"));

        validateSetting(setting);
        if (!Boolean.TRUE.equals(setting.getEnabled())) {
            throw new BusinessException("该贷款设置已禁用");
        }

        if (setting.getMinAmount() != null && amount.compareTo(setting.getMinAmount()) < 0) {
            throw new BusinessException("贷款金额不能小于" + setting.getMinAmount());
        }

        if (setting.getMaxAmount() != null && amount.compareTo(setting.getMaxAmount()) > 0) {
            throw new BusinessException("贷款金额不能大于" + setting.getMaxAmount());
        }

        if(requestKey!=null) {
            userAccountRepository.lockById(userId).orElseThrow(()->new BusinessException("用户不存在"));
            LoanRecord previous=loanRecordRepository.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId,requestKey).orElse(null);
            if(previous!=null) {OrderRequest.same(previous.getRequestHash(),requestHash);return previous;}
        }
        // 验证用户存在
        userAccountRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        com.gtcfesk.exchange.entity.LoanPersonalInfo approved = loanPersonalInfoService.requireApprovedPersonalInfo(userId);

        // 计算利息（考虑免息天数）
        int chargeableDays = Math.max(0, setting.getDays() - setting.getFreeDays());
        BigDecimal dailyRateDecimal = setting.getDailyRate().divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
        BigDecimal totalInterest = amount.multiply(dailyRateDecimal).multiply(new BigDecimal(chargeableDays))
                .setScale(16, RoundingMode.HALF_UP);

        BigDecimal repaymentAmount = amount.add(totalInterest);

        LoanRecord record = new LoanRecord();
        record.setRequestKey(requestKey);record.setRequestHash(requestHash);
        record.setUserId(userId);
        record.setAmount(amount);
        record.setDays(setting.getDays());
        record.setDailyRate(setting.getDailyRate());
        record.setFreeDays(setting.getFreeDays());
        record.setTotalInterest(totalInterest);
        record.setOverdueRate(setting.getOverdueRate());
        record.setRepaymentAmount(repaymentAmount);
        record.setStatus("PENDING");
        
        // 设置实名信息
        record.setRealName(approved.getRealName());
        record.setIdNumber(approved.getIdNumber());
        record.setPhone(approved.getPhone());
        record.setAddress(approved.getAddress());

        return loanRecordRepository.save(record);
    }

    /** Optional amount bounds/overdue rate retain their existing nullable model semantics. */
    static void validateSetting(LoanSetting setting) {
        if (setting.getDays() == null || setting.getDays() <= 0 || setting.getFreeDays() == null
                || setting.getFreeDays() < 0 || setting.getFreeDays() > setting.getDays())
            throw new BusinessException("贷款期限或免息期配置异常");
        com.gtcfesk.exchange.common.TradeValidation.nonNegative(setting.getDailyRate(), "贷款日利率");
        if (setting.getOverdueRate() != null) com.gtcfesk.exchange.common.TradeValidation.nonNegative(setting.getOverdueRate(), "贷款逾期费率");
        com.gtcfesk.exchange.common.TradeValidation.optionalPositive(setting.getMinAmount(), "贷款最低金额");
        com.gtcfesk.exchange.common.TradeValidation.optionalPositive(setting.getMaxAmount(), "贷款最高金额");
        if (setting.getMinAmount() != null && setting.getMaxAmount() != null && setting.getMaxAmount().compareTo(setting.getMinAmount()) < 0)
            throw new BusinessException("贷款金额范围配置异常");
    }

    /**
     * 获取用户的实名认证信息（用于自动填入）
     */
    public Map<String, String> getKycInfoForLoan(Long userId) {
        Map<String, String> info = new HashMap<>();
        UserAccount user = userAccountRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId).orElse(null);
        if (user == null) {
            return info;
        }

        // 从用户表获取电话
        if (user.getPhone() != null) {
            info.put("phone", user.getPhone());
        }

        // 从实名认证记录获取姓名和身份证号
        Optional<KycRecord> kycRecord = kycRecordRepository.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId);
        if (kycRecord.isPresent() && "APPROVED".equals(kycRecord.get().getStatus())) {
            KycRecord record = kycRecord.get();
            if (record.getRealName() != null) {
                info.put("realName", record.getRealName());
            }
            if (record.getIdNumber() != null) {
                info.put("idNumber", record.getIdNumber());
            }
        }

        return info;
    }

    @Transactional
    public LoanRecord signContract(Long loanId, Long userId, String signatureImage) {
        LoanRecord record = loanRecordRepository.lockById(loanId)
                .orElseThrow(() -> new BusinessException("贷款记录不存在"));

        if (!record.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权签署该贷款");
        }
        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException("该贷款记录状态不允许签署合同");
        }

        tenantPolicy.requireNewBusiness("loan");
        record.setContractSigned(true);
        record.setSignatureImage(signatureImage);
        record.setStatus("SIGNED");
        record.setRepaymentDate(LocalDateTime.now().plusDays(record.getDays()));
        if (simulation != null && simulation.enabled()) {
            loanRecordRepository.saveAndFlush(record);
            simulationReview.approveLoan(record.getId());
        }

        return loanRecordRepository.save(record);
    }

    public List<LoanRecord> getUserLoans(Long userId) {
        return loanRecordRepository.findByTenantIdAndUserIdOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId);
    }

    public LoanRecord getLoanById(Long loanId) {
        return loanRecordRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), loanId)
                .orElseThrow(() -> new BusinessException("贷款记录不存在"));
    }

    /**
     * 获取用户借款总金额（所有未还清的贷款金额总和）
     */
    public BigDecimal getTotalLoanAmount(Long userId) {
        List<LoanRecord> loans = loanRecordRepository.findByTenantIdAndUserIdOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId);
        BigDecimal total = BigDecimal.ZERO;
        for (LoanRecord loan : loans) {
            // 只统计未还清的贷款（非COMPLETED和REJECTED状态）
            if (!"COMPLETED".equals(loan.getStatus()) && !"REJECTED".equals(loan.getStatus())) {
                total = total.add(loan.getAmount());
            }
        }
        return total;
    }

    /**
     * 提前还款
     * 根据实际使用天数计算利息，从资金账户扣除
     */
    @Transactional
    public LoanRecord earlyRepayment(Long loanId, Long userId) {
        LoanRecord record = loanRecordRepository.lockById(loanId)
                .orElseThrow(() -> new BusinessException("贷款记录不存在"));

        // 验证用户权限
        if (!record.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该贷款记录");
        }

        userAccountRepository.lockById(userId).orElseThrow(() -> new BusinessException("用户不存在"));
        if ("COMPLETED".equals(record.getStatus()) && record.getActualRepaymentAt()!=null) return record;
        if (!"APPROVED".equals(record.getStatus()) || record.getApprovedAt()==null || record.getActualRepaymentAt()!=null) {
            throw new BusinessException("只有已放款且未还清的贷款才能提前还款");
        }
        com.gtcfesk.exchange.common.TradeValidation.positive(record.getAmount(), "贷款金额");

        // 计算实际使用天数
        LocalDateTime startDate = record.getApprovedAt() != null ? record.getApprovedAt() : record.getCreatedAt();
        long actualDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, LocalDateTime.now());
        if (actualDays < 0) {
            actualDays = 0;
        }

        // 计算实际利息（考虑免息天数）
        BigDecimal actualInterest = LoanInterest.accrued(record.getAmount(), record.getDailyRate(),
                record.getFreeDays(), actualDays);

        // 计算实际还款金额（本金 + 实际利息）
        BigDecimal actualRepaymentAmount = record.getAmount().add(actualInterest);
        if(actualInterest.signum()<0)throw new BusinessException("贷款利息配置异常");

        // 获取用户的资金账户
        AssetAccount fundAccount = assetAccountRepository.lockByUserId(userId).stream().filter(a -> "FUND".equals(a.getCoin())).findFirst()
                .orElseThrow(() -> new BusinessException("资金账户不存在"));

        // 检查余额是否足够
        BigDecimal available = fundAccount.getAvailable() != null ? fundAccount.getAvailable() : BigDecimal.ZERO;
        if (available.compareTo(actualRepaymentAmount) < 0) {
            throw new BusinessException("资金账户余额不足，当前余额: " + available + "，需要: " + actualRepaymentAmount);
        }

        // 扣除还款金额
        fundAccount.setAvailable(available.subtract(actualRepaymentAmount));
        assetAccountRepository.save(fundAccount);

        // 更新贷款记录
        record.setStatus("COMPLETED");
        record.setActualRepaymentAt(LocalDateTime.now());
        record.setTotalInterest(actualInterest); // 更新为实际利息
        record.setRepaymentAmount(actualRepaymentAmount); // 更新为实际还款金额

        loanRecordRepository.save(record);
        audit.recordCurrent("LOAN_REPAY",loanId.toString(),"userId="+userId+"; repayment="+actualRepaymentAmount+"; availableBefore="+available+"; availableAfter="+fundAccount.getAvailable()+"; status=APPROVED/COMPLETED",null);
        return record;
    }
}

