package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.DepositRecord;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.DepositRecordRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DepositReviewService {

    private final DepositRecordRepository depositRecordRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final UserAccountRepository userAccountRepository;

    public List<DepositRecord> getDepositRecords(String status, Long userId, String userEmail, Long agentId) {
        List<DepositRecord> allRecords;
        
        // 如果是代理，只返回下级用户的充值记录
        if (agentId != null) {
            // 获取所有下级用户ID
            List<UserAccount> subordinates = userAccountRepository.findByParentUserId(agentId);
            Set<Long> subordinateUserIds = subordinates.stream()
                    .map(UserAccount::getId)
                    .collect(Collectors.toSet());
            
            if (subordinateUserIds.isEmpty()) {
                // 没有下级用户，返回空列表
                return new java.util.ArrayList<>();
            }
            
            // 只查询下级用户的充值记录
            allRecords = depositRecordRepository.findAll().stream()
                    .filter(record -> subordinateUserIds.contains(record.getUserId()))
                    .collect(Collectors.toList());
        } else {
            // 管理员查看所有记录
            allRecords = depositRecordRepository.findAll().stream()
                    .collect(Collectors.toList());
        }
        
        // 按状态过滤
        Stream<DepositRecord> filteredStream = allRecords.stream();
        if (status != null && !status.isEmpty()) {
            filteredStream = filteredStream.filter(record -> status.equals(record.getStatus()));
        }
        
        // 按用户ID过滤
        if (userId != null) {
            filteredStream = filteredStream.filter(record -> record.getUserId() != null && record.getUserId().equals(userId));
        }
        
        // 按用户邮箱过滤
        if (userEmail != null && !userEmail.trim().isEmpty()) {
            // 先通过邮箱查找用户ID
            Optional<UserAccount> userOpt = userAccountRepository.findByEmail(userEmail.trim());
            if (userOpt.isPresent()) {
                Long targetUserId = userOpt.get().getId();
                filteredStream = filteredStream.filter(record -> record.getUserId() != null && record.getUserId().equals(targetUserId));
            } else {
                // 用户不存在，返回空列表
                return new java.util.ArrayList<>();
            }
        }
        
        // 按创建时间倒序排序
        List<DepositRecord> sortedRecords = filteredStream
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) {
                        return 0;
                    }
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .collect(Collectors.toList());
        
        // 为每个充值记录填充代理信息
        for (DepositRecord record : sortedRecords) {
            fillAgentInfo(record);
        }
        
        return sortedRecords;
    }
    
    /**
     * 填充充值记录的代理信息
     */
    private void fillAgentInfo(DepositRecord record) {
        if (record.getUserId() == null) {
            return;
        }
        
        // 查找用户信息
        UserAccount user = userAccountRepository.findById(record.getUserId()).orElse(null);
        if (user == null) {
            return;
        }
        
        // 如果用户有上级代理，填充代理信息
        if (user.getParentUserId() != null) {
            UserAccount agent = userAccountRepository.findById(user.getParentUserId()).orElse(null);
            if (agent != null) {
                // 格式：所属代理:用户名（优先使用昵称，没有则使用邮箱）
                String agentName = agent.getNickname();
                if (agentName == null || agentName.isEmpty()) {
                    agentName = agent.getEmail();
                }
                record.setAgentInfo("所属代理:" + agentName);
            }
        }
    }

    @Transactional
    public void approveDeposit(Long recordId) {
        DepositRecord record = depositRecordRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("充值记录不存在"));

        if (!"PENDING".equals(record.getStatus())) {
            throw new IllegalArgumentException("该充值记录已处理，无法重复审核");
        }

        // 更新记录状态为已完成
        record.setStatus("COMPLETED");
        depositRecordRepository.save(record);

        // 充值到用户的FUND账户
        Long userId = record.getUserId();
        BigDecimal amount = record.getAmount();

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
        fundAccount.setAvailable(currentAvailable.add(amount));
        assetAccountRepository.save(fundAccount);
    }

    @Transactional
    public void rejectDeposit(Long recordId, String remark) {
        DepositRecord record = depositRecordRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("充值记录不存在"));

        if (!"PENDING".equals(record.getStatus())) {
            throw new IllegalArgumentException("该充值记录已处理，无法重复审核");
        }

        // 更新记录状态为已拒绝
        record.setStatus("REJECTED");
        if (remark != null && !remark.isEmpty()) {
            record.setRemark(remark);
        }
        depositRecordRepository.save(record);
    }
}

