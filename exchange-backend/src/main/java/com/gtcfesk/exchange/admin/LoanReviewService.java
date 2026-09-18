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

    private final LoanRecordRepository loanRecordRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final UserAccountRepository userAccountRepository;

    public List<LoanRecord> getLoanRecords(String status, Long userId, String userEmail, Long agentId) {
        List<LoanRecord> records;
        if (status != null && !status.isEmpty()) {
            records = loanRecordRepository.findAll().stream()
                    .filter(record -> status.equals(record.getStatus()))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .collect(java.util.stream.Collectors.toList());
        } else {
            records = loanRecordRepository.findAllByOrderByCreatedAtDesc();
        }
        
        // 如果指定了代理ID，只返回该代理下级用户的记录
        if (agentId != null) {
            List<UserAccount> subordinates = userAccountRepository.findByParentUserId(agentId);
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
            Optional<UserAccount> userOpt = userAccountRepository.findByEmail(userEmail.trim());
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
            UserAccount user = userAccountRepository.findById(record.getUserId()).orElse(null);
            if (user != null && user.getParentUserId() != null) {
                UserAccount agent = userAccountRepository.findById(user.getParentUserId()).orElse(null);
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
        LoanRecord record = loanRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("贷款记录不存在"));

        if (!"SIGNED".equals(record.getStatus())) {
            throw new RuntimeException("只有已签约的贷款才能审核通过");
        }

        // 更新贷款记录状态
        record.setStatus("APPROVED");
        record.setApprovedAt(LocalDateTime.now());
        loanRecordRepository.save(record);

        // 将贷款金额添加到用户的资金账户（FUND账户）
        Long userId = record.getUserId();
        BigDecimal loanAmount = record.getAmount();

        AssetAccount fundAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "FUND")
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
    }

    @Transactional
    public void rejectLoan(Long id, String remark) {
        LoanRecord record = loanRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("贷款记录不存在"));

        record.setStatus("REJECTED");
        record.setRemark(remark);
        loanRecordRepository.save(record);
    }
}

