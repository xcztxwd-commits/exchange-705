package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.LoanRecord;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.LoanRecordRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LoanReviewService {
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService audit;

    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;

    private final LoanRecordRepository loanRecordRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final UserAccountRepository userAccountRepository;
    private final com.gtcfesk.exchange.user.LoanPersonalInfoService loanPersonalInfoService;

    public List<LoanRecord> getLoanRecords(String status, Long userId, String userEmail, Long agentId) {
        List<LoanRecord> records;
        if (status != null && !status.isEmpty()) {
            records = loanRecordRepository.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()).stream()
                    .filter(record -> status.equals(record.getStatus()))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .collect(java.util.stream.Collectors.toList());
        } else {
            records = loanRecordRepository.findAllByTenantIdOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        }
        
        // 如果指定了代理ID，只返回该代理下级用户的记录
        if (agentId != null) {
            List<UserAccount> subordinates = userAccountRepository.findByTenantIdAndParentUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), agentId);
            Set<Long> subordinateUserIds = subordinates.stream()
                    .map(UserAccount::getId)
                    .collect(Collectors.toSet());
            
            if (!subordinateUserIds.isEmpty()) {
                records = records.stream()
                        .filter(record -> subordinateUserIds.contains(record.getUserId()))
                        .collect(Collectors.toList());
            } else {
                records = new java.util.ArrayList<>();
            }
        }
        
        // 按用户ID过滤
        if (userId != null) {
            records = records.stream()
                    .filter(record -> record.getUserId() != null && record.getUserId().equals(userId))
                    .collect(Collectors.toList());
        }
        
        // 按用户邮箱过滤
        if (userEmail != null && !userEmail.trim().isEmpty()) {
            Optional<UserAccount> userOpt = userAccountRepository.findByTenantIdAndEmail(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userEmail.trim());
            if (userOpt.isPresent()) {
                Long targetUserId = userOpt.get().getId();
                records = records.stream()
                        .filter(record -> record.getUserId() != null && record.getUserId().equals(targetUserId))
                        .collect(Collectors.toList());
            } else {
                // 用户不存在，返回空列表
                records = new java.util.ArrayList<>();
            }
        }
        
        // 填充代理信息
        records.forEach(record -> {
            UserAccount user = userAccountRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), record.getUserId()).orElse(null);
            if (user != null && user.getParentUserId() != null) {
                UserAccount agent = userAccountRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), user.getParentUserId()).orElse(null);
                if (agent != null) {
                    String agentName = (agent.getNickname() != null && !agent.getNickname().isEmpty()) 
                            ? agent.getNickname() : agent.getEmail();
                    record.setAgentInfo("所属代理:" + agentName);
                }
            }
        });
        
        return records;
    }

    @Transactional
    public void approveLoan(Long id) {
        LoanRecord record = loanRecordRepository.lockById(id)
                .orElseThrow(() -> new RuntimeException("贷款记录不存在"));

        userAccountRepository.lockById(record.getUserId()).orElseThrow(() -> new com.gtcfesk.exchange.common.BusinessException("用户不存在"));
        if ("APPROVED".equals(record.getStatus())) return;
        if (!"SIGNED".equals(record.getStatus())) {
            throw new RuntimeException("只有已签约的贷款才能审核通过");
        }

        tenantPolicy.requireNewBusiness("loan");
        com.gtcfesk.exchange.entity.LoanPersonalInfo approved = loanPersonalInfoService.requireApprovedPersonalInfo(record.getUserId());
        if (!java.util.Objects.equals(record.getRealName(), approved.getRealName())
                || !java.util.Objects.equals(record.getIdNumber(), approved.getIdNumber())) {
            throw new com.gtcfesk.exchange.common.BusinessException("贷款申请身份与已审核实名不一致，请重新申请贷款");
        }

        com.gtcfesk.exchange.common.TradeValidation.positive(record.getAmount(), "贷款金额");
        if (record.getDays() == null || record.getDays() <= 0) {
            throw new com.gtcfesk.exchange.common.BusinessException("贷款期限配置异常");
        }
        // Repayment maturity starts with actual disbursement, not an earlier signature.
        LocalDateTime approvedAt = LocalDateTime.now();
        record.setRepaymentDate(approvedAt.plusDays(record.getDays()));
        // 更新贷款记录状态
        record.setStatus("APPROVED");
        record.setApprovedAt(approvedAt);
        loanRecordRepository.save(record);

        // 将贷款金额添加到用户的资金账户（FUND账户）
        Long userId = record.getUserId();
        BigDecimal loanAmount = record.getAmount();

        AssetAccount fundAccount = assetAccountRepository.lockByUserId(userId).stream().filter(a -> "FUND".equals(a.getCoin())).findFirst()
                .orElseGet(() -> {
                    AssetAccount newAccount = new AssetAccount();
                    newAccount.setUserId(userId);
                    newAccount.setCoin("FUND");
                    newAccount.setAvailable(BigDecimal.ZERO);
                    newAccount.setFrozen(BigDecimal.ZERO);
                    return assetAccountRepository.save(newAccount);
                });

        // 增加可用余额
        BigDecimal currentAvailable = fundAccount.getAvailable() != null 
                ? fundAccount.getAvailable() 
                : BigDecimal.ZERO;
        fundAccount.setAvailable(currentAvailable.add(loanAmount));
        assetAccountRepository.save(fundAccount);
        audit.recordCurrent("LOAN_APPROVE", id.toString(), "userId="+userId+"; amount="+loanAmount+"; availableBefore="+currentAvailable+"; availableAfter="+fundAccount.getAvailable()+"; status=SIGNED/APPROVED", null);
    }

    @Transactional
    public void rejectLoan(Long id, String remark) {
        LoanRecord record = loanRecordRepository.lockById(id)
                .orElseThrow(() -> new RuntimeException("贷款记录不存在"));

        userAccountRepository.lockById(record.getUserId()).orElseThrow(() -> new com.gtcfesk.exchange.common.BusinessException("用户不存在"));
        if ("REJECTED".equals(record.getStatus())) return;
        if (!"PENDING".equals(record.getStatus()) && !"SIGNED".equals(record.getStatus())) {
            throw new com.gtcfesk.exchange.common.BusinessException("当前贷款状态不允许拒绝");
        }
        String before=record.getStatus();
        record.setStatus("REJECTED");
        record.setRemark(remark);
        loanRecordRepository.save(record);
        audit.recordCurrent("LOAN_REJECT", id.toString(), "userId="+record.getUserId()+"; statusBefore="+before+"; statusAfter=REJECTED", remark);
    }
}

