package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.admin.dto.ResetPasswordRequest;
import com.gtcfesk.exchange.admin.dto.UpdateUserStatusRequest;
import com.gtcfesk.exchange.admin.dto.UserQueryRequest;
import com.gtcfesk.exchange.admin.dto.UpdateUserBalanceRequest;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.ContractOrderRepository;
import com.gtcfesk.exchange.repository.OptionOrderRepository;
import com.gtcfesk.exchange.repository.DepositRecordRepository;
import com.gtcfesk.exchange.repository.WithdrawRecordRepository;
import com.gtcfesk.exchange.repository.LoanRecordRepository;
import com.gtcfesk.exchange.repository.FinancialOrderRepository;
import com.gtcfesk.exchange.repository.TransferRecordRepository;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.entity.OptionOrder;
import com.gtcfesk.exchange.entity.DepositRecord;
import com.gtcfesk.exchange.entity.WithdrawRecord;
import com.gtcfesk.exchange.entity.LoanRecord;
import com.gtcfesk.exchange.entity.FinancialOrder;
import com.gtcfesk.exchange.entity.TransferRecord;
import com.gtcfesk.exchange.service.EmailService;
import com.gtcfesk.exchange.admin.AgentMenuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;

@Service
public class AdminUserService {
    @Autowired
    private com.gtcfesk.exchange.user.FiatCurrencyService fiatCurrencyService;
    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Autowired
    private AssetAccountRepository assetAccountRepository;

    @Autowired
    private AgentMenuService agentMenuService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private ContractOrderRepository contractOrderRepository;

    @Autowired
    private OptionOrderRepository optionOrderRepository;

    @Autowired
    private DepositRecordRepository depositRecordRepository;

    @Autowired
    private WithdrawRecordRepository withdrawRecordRepository;

    @Autowired
    private LoanRecordRepository loanRecordRepository;

    @Autowired
    private FinancialOrderRepository financialOrderRepository;

    @Autowired
    private TransferRecordRepository transferRecordRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public Page<UserAccount> queryUsers(UserQueryRequest req) {
        Long callerAgent = com.gtcfesk.exchange.config.BackendAccess.agentId();
        req.setAgentId(callerAgent != null ? callerAgent : req.getFilterAgentId());
        PageRequest pageRequest = PageRequest.of(
            req.getPage(), 
            req.getSize(),
            Sort.by(Sort.Direction.DESC, "createdAt")
        );
        
        // 如果是代理查询，只返回下级用户
        if (req.getAgentId() != null) {
            List<UserAccount> subordinates = userAccountRepository.findByParentUserId(req.getAgentId());
            // 应用过滤条件
            if (req.getUserId() != null) {
                subordinates = subordinates.stream()
                    .filter(user -> req.getUserId().equals(user.getId()))
                    .collect(java.util.stream.Collectors.toList());
            }
            if (req.getKeyword() != null && !req.getKeyword().isEmpty()) {
                subordinates = subordinates.stream()
                    .filter(user -> 
                        (user.getEmail() != null && user.getEmail().contains(req.getKeyword())) ||
                        (user.getNickname() != null && user.getNickname().contains(req.getKeyword())) ||
                        (user.getPhone() != null && user.getPhone().contains(req.getKeyword()))
                    )
                    .collect(java.util.stream.Collectors.toList());
            }
            if (req.getStatus() != null && !req.getStatus().isEmpty()) {
                subordinates = subordinates.stream()
                    .filter(user -> req.getStatus().equals(user.getStatus()))
                    .collect(java.util.stream.Collectors.toList());
            }
            if (req.getUserType() != null && !req.getUserType().isEmpty()) {
                subordinates = subordinates.stream()
                    .filter(user -> req.getUserType().equals(user.getUserType()))
                    .collect(java.util.stream.Collectors.toList());
            }
            
            // 手动分页
            int start = Math.min(req.getPage() * req.getSize(), subordinates.size());
            int end = Math.min(start + req.getSize(), subordinates.size());
            List<UserAccount> pageContent = subordinates.subList(start, end);
            pageContent.forEach(user -> {
                fillBalances(user);
                fillParentUser(user);
                fillSubordinateCount(user);
                fillUserTypeLabel(user);
            });
            
            return new org.springframework.data.domain.PageImpl<>(
                pageContent, 
                pageRequest, 
                subordinates.size()
            );
        }
        
        // 如果管理员传入了筛选代理ID，只返回该代理的下级用户
        if (req.getFilterAgentId() != null) {
            List<UserAccount> subordinates = userAccountRepository.findByParentUserId(req.getFilterAgentId());
            // 应用过滤条件
            if (req.getUserId() != null) {
                subordinates = subordinates.stream()
                    .filter(user -> req.getUserId().equals(user.getId()))
                    .collect(java.util.stream.Collectors.toList());
            }
            if (req.getKeyword() != null && !req.getKeyword().isEmpty()) {
                subordinates = subordinates.stream()
                    .filter(user -> 
                        (user.getEmail() != null && user.getEmail().contains(req.getKeyword())) ||
                        (user.getNickname() != null && user.getNickname().contains(req.getKeyword())) ||
                        (user.getPhone() != null && user.getPhone().contains(req.getKeyword()))
                    )
                    .collect(java.util.stream.Collectors.toList());
            }
            if (req.getStatus() != null && !req.getStatus().isEmpty()) {
                subordinates = subordinates.stream()
                    .filter(user -> req.getStatus().equals(user.getStatus()))
                    .collect(java.util.stream.Collectors.toList());
            }
            if (req.getUserType() != null && !req.getUserType().isEmpty()) {
                subordinates = subordinates.stream()
                    .filter(user -> req.getUserType().equals(user.getUserType()))
                    .collect(java.util.stream.Collectors.toList());
            }
            
            // 手动分页
            int start = Math.min(req.getPage() * req.getSize(), subordinates.size());
            int end = Math.min(start + req.getSize(), subordinates.size());
            List<UserAccount> pageContent = start < subordinates.size() 
                    ? subordinates.subList(start, end) 
                    : new java.util.ArrayList<>();
            pageContent.forEach(user -> {
                fillBalances(user);
                fillParentUser(user);
                fillSubordinateCount(user);
                fillUserTypeLabel(user);
            });
            
            return new org.springframework.data.domain.PageImpl<>(
                pageContent, 
                pageRequest, 
                subordinates.size()
            );
        }
        
        // 如果指定了用户ID，直接查询
        if (req.getUserId() != null) {
            Optional<UserAccount> userOpt = userAccountRepository.findById(req.getUserId());
            if (userOpt.isPresent()) {
                UserAccount user = userOpt.get();
                // 应用其他过滤条件
                boolean matches = true;
                if (req.getKeyword() != null && !req.getKeyword().isEmpty()) {
                    String keyword = req.getKeyword().toLowerCase();
                    matches = matches && (
                        (user.getEmail() != null && user.getEmail().toLowerCase().contains(keyword)) ||
                        (user.getNickname() != null && user.getNickname().toLowerCase().contains(keyword)) ||
                        (user.getPhone() != null && user.getPhone().contains(keyword))
                    );
                }
                if (req.getStatus() != null && !req.getStatus().isEmpty()) {
                    matches = matches && req.getStatus().equals(user.getStatus());
                }
                if (req.getUserType() != null && !req.getUserType().isEmpty()) {
                    matches = matches && req.getUserType().equals(user.getUserType());
                }
                
                if (matches) {
                    fillBalances(user);
                    fillParentUser(user);
                    fillSubordinateCount(user);
                    fillUserTypeLabel(user);
                    List<UserAccount> singleList = java.util.Collections.singletonList(user);
                    return new org.springframework.data.domain.PageImpl<>(
                        singleList,
                        pageRequest,
                        1
                    );
                } else {
                    return new org.springframework.data.domain.PageImpl<>(
                        java.util.Collections.emptyList(),
                        pageRequest,
                        0
                    );
                }
            } else {
                // 用户不存在，返回空列表
                return new org.springframework.data.domain.PageImpl<>(
                    java.util.Collections.emptyList(),
                    pageRequest,
                    0
                );
            }
        }
        
        Page<UserAccount> page = userAccountRepository.searchUsers(
            req.getKeyword(), 
            req.getStatus(),
            req.getUserType(),
            pageRequest
        );
        // 为列表中的每个用户补充资产信息、上级用户信息和下级数量
        page.getContent().forEach(user -> {
            fillBalances(user);
            fillParentUser(user);
            fillSubordinateCount(user);
            fillUserTypeLabel(user);
        });
        return page;
    }

    private void fillParentUser(UserAccount user) {
        if (user.getParentUserId() != null) {
            userAccountRepository.findById(user.getParentUserId())
                    .ifPresent(parent -> {
                        user.setParentUserEmail(parent.getEmail());
                    });
        }
    }

    public void resetPassword(ResetPasswordRequest req) {
        UserAccount user = userAccountRepository.findById(req.getUserId())
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        user.setCurrentToken(null);
        user.setUpdatedAt(LocalDateTime.now());
        userAccountRepository.save(user);
        
        // 发送邮件通知
        try {
            emailService.sendPasswordResetNotice(user.getEmail());
        } catch (Exception e) {
            System.err.println("邮件发送失败: " + e.getMessage());
        }
    }

    public void updateStatus(UpdateUserStatusRequest req) {
        UserAccount user = userAccountRepository.findById(req.getUserId())
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setStatus(req.getStatus());
        user.setCurrentToken(null);
        user.setUpdatedAt(LocalDateTime.now());
        userAccountRepository.save(user);
    }

    public void updateUserType(com.gtcfesk.exchange.admin.dto.UpdateUserTypeRequest req) {
        UserAccount user = userAccountRepository.findById(req.getUserId())
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setUserType(req.getUserType());
        user.setUpdatedAt(LocalDateTime.now());
        userAccountRepository.save(user);
    }

    public UserAccount getUserDetail(Long userId) {
        UserAccount user = userAccountRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        fillBalances(user);
        fillUserTypeLabel(user);
        return user;
    }

    @Transactional
    public void updateBalance(UpdateUserBalanceRequest req) {
        Long userId = req.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        if (req.getAmount() != null) {
            if (!java.util.Arrays.asList("FUND", "CONTRACT", "OPTION").contains(req.getAccount())) {
                throw new IllegalArgumentException("无效账户");
            }
            if (!userAccountRepository.existsById(userId)) throw new IllegalArgumentException("用户不存在");
            BigDecimal usd = fiatCurrencyService.toUsd(req.getAmount(), fiatCurrencyService.rate(req.getCurrency()));
            AssetAccount account = assetAccountRepository.findByUserIdAndCoin(userId, req.getAccount())
                    .orElseGet(() -> {
                        AssetAccount created = new AssetAccount();
                        created.setUserId(userId);
                        created.setCoin(req.getAccount());
                        return created;
                    });
            account.setAvailable((account.getAvailable() == null ? BigDecimal.ZERO : account.getAvailable()).add(usd));
            assetAccountRepository.save(account);
            return;
        }
        updateSingleBalance(userId, "FUND", req.getFundBalance());
        updateSingleBalance(userId, "CONTRACT", req.getContractBalance());
        updateSingleBalance(userId, "OPTION", req.getOptionBalance());
    }

    private void updateSingleBalance(Long userId, String coin, BigDecimal value) {
        if (value == null) {
            return;
        }
        AssetAccount account = assetAccountRepository
                .findByUserIdAndCoin(userId, coin)
                .orElseGet(() -> {
                    AssetAccount a = new AssetAccount();
                    a.setUserId(userId);
                    a.setCoin(coin);
                    return a;
                });
        account.setAvailable(value);
        assetAccountRepository.save(account);
    }

    private void fillBalances(UserAccount user) {
        List<AssetAccount> assets = assetAccountRepository.findByUserId(user.getId());
        BigDecimal fund = BigDecimal.ZERO;
        BigDecimal contract = BigDecimal.ZERO;
        BigDecimal option = BigDecimal.ZERO;

        for (AssetAccount a : assets) {
            BigDecimal total = a.getAvailable() == null ? BigDecimal.ZERO : a.getAvailable();
            String coin = a.getCoin() == null ? "" : a.getCoin().toUpperCase();
            switch (coin) {
                case "FUND":
                    fund = total;
                    break;
                case "CONTRACT":
                    contract = total;
                    break;
                case "OPTION":
                    option = total;
                    break;
                default:
                    break;
            }
        }
        user.setFundBalance(fund);
        user.setContractBalance(contract);
        user.setOptionBalance(option);
        // 映射余额字段供前端使用
        user.setUsdtBalance(fund); // FUND账户余额映射为USDT余额
        user.setCnyBalance(BigDecimal.ZERO); // CNY余额暂时设为0，可根据实际业务调整
    }

    /**
     * 填充用户的下级数量
     */
    private void fillSubordinateCount(UserAccount user) {
        if (user.getId() != null) {
            List<UserAccount> subordinates = userAccountRepository.findByParentUserId(user.getId());
            user.setSubordinateCount(subordinates != null ? subordinates.size() : 0);
        } else {
            user.setSubordinateCount(0);
        }
    }

    /**
     * 填充用户类型标签（真人/假人）
     * 根据登录域名是否在白名单中判断
     */
    private void fillUserTypeLabel(UserAccount user) {
        String domain = user.getLastLoginDomain();
        if (domain == null || domain.isEmpty()) {
            user.setUserTypeLabel("假人");
            return;
        }

        // 获取域名白名单配置
        String whitelistStr = systemConfigService.getConfigValue("domain.whitelist");
        if (whitelistStr == null || whitelistStr.isEmpty()) {
            // 如果没有配置白名单，默认所有域名都是假人
            user.setUserTypeLabel("假人");
            return;
        }

        // 检查域名是否在白名单中
        boolean isInWhitelist = com.gtcfesk.exchange.utils.DomainUtils.isDomainInWhitelist(domain, whitelistStr);
        user.setUserTypeLabel(isInWhitelist ? "真人" : "假人");
    }

    public List<UserAccount> getSubordinates(Long userId) {
        List<UserAccount> subordinates = userAccountRepository.findByParentUserId(userId);
        subordinates.forEach(this::fillBalances);
        return subordinates;
    }

    /**
     * 删除用户及所有关联数据
     */
    @Transactional
    public void deleteUser(Long userId) {
        userAccountRepository.lockById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        List<AssetAccount> assets = assetAccountRepository.lockByUserId(userId);
        if (assets.stream().anyMatch(x -> (x.getAvailable() != null && x.getAvailable().signum() != 0)
                || (x.getFrozen() != null && x.getFrozen().signum() != 0))) {
            throw new com.gtcfesk.exchange.common.BusinessException("账户存在余额或冻结资金，不能删除，请使用禁用功能");
        }
        String[] history = {"contract_order", "option_order", "deposit_record", "withdraw_record", "loan_record",
                "financial_order", "financial_yield_record", "transfer_record", "kyc_record", "loan_personal_info"};
        for (String table : history) {
            Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?")
                    .setParameter(1, userId).getSingleResult();
            if (count.longValue() != 0) throw new com.gtcfesk.exchange.common.BusinessException("账户存在业务历史，不能删除，请使用禁用功能");
        }
        if (!userAccountRepository.findByParentUserId(userId).isEmpty()) {
            throw new com.gtcfesk.exchange.common.BusinessException("账户存在下级用户，不能删除");
        }
        for (String table : new String[]{"user_action", "user_menu", "user_bank_card", "user_digital_address"}) {
            entityManager.createNativeQuery("DELETE FROM " + table + " WHERE user_id = ?").setParameter(1, userId).executeUpdate();
        }
        assetAccountRepository.deleteAll(assets);
        userAccountRepository.deleteById(userId);
        entityManager.flush();
    }

    /**
     * 为代理分配菜单权限
     */
    @Transactional
    public void assignAgentMenus(Long agentId, List<Long> menuIds) {
        agentMenuService.assignMenus(agentId, menuIds);
    }

    /**
     * 获取代理的菜单ID列表
     */
    public List<Long> getAgentMenuIds(Long agentId) {
        return agentMenuService.getAgentMenuIds(agentId);
    }

    /**
     * 获取所有代理用户列表（用于筛选）
     */
    public List<UserAccount> getAllAgents() {
        return userAccountRepository.findAll().stream()
                .filter(user -> "agent".equals(user.getUserType()))
                .filter(user -> com.gtcfesk.exchange.config.BackendAccess.agentId() == null || user.getId().equals(com.gtcfesk.exchange.config.BackendAccess.agentId()))
                .filter(user -> "active".equals(user.getStatus()) || "normal".equals(user.getStatus()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 获取用户资金明细
     */
    public Map<String, Object> getUserFundDetails(Long userId) {
        Map<String, Object> result = new HashMap<>();
        
        // 1. 合约订单
        List<ContractOrder> contractOrders = contractOrderRepository.findByUserIdOrderByCreatedAtDesc(userId);
        result.put("contractOrders", contractOrders);
        
        // 2. 期权订单
        List<OptionOrder> optionOrders = optionOrderRepository.findByUserIdOrderByCreatedAtDesc(userId);
        result.put("optionOrders", optionOrders);
        
        // 3. 充值记录（入金）
        List<DepositRecord> deposits = depositRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
        result.put("deposits", deposits);
        
        // 4. 提现记录（出金）
        List<WithdrawRecord> withdraws = withdrawRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
        result.put("withdraws", withdraws);
        
        // 5. 贷款记录
        List<LoanRecord> loans = loanRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
        result.put("loans", loans);
        
        // 6. 理财订单
        List<FinancialOrder> financialOrders = financialOrderRepository.findByUserIdOrderByPurchaseTimeDesc(userId);
        result.put("financialOrders", financialOrders);
        
        // 7. 转账记录
        List<TransferRecord> transfers = transferRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
        result.put("transfers", transfers);
        
        return result;
    }

    /**
     * 获取在线用户数
     * @param agentId 代理ID，如果为null则统计所有用户，否则只统计该代理的下级用户
     */
    public int getOnlineUserCount(Long agentId) {
        List<UserAccount> users;
        if (agentId != null) {
            // 代理：只统计下级用户
            users = userAccountRepository.findByParentUserId(agentId);
        } else {
            // 管理员：统计所有用户
            users = userAccountRepository.findAll();
        }
        
        // 统计在线用户数（最后活动时间在5分钟内认为在线）
        // 使用 lastActivityAt 而不是 lastLoginAt，因为 lastActivityAt 会在每次请求时更新
        int onlineCount = 0;
        LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
        
        for (UserAccount user : users) {
            // 优先使用 lastActivityAt（实时更新），如果为空则使用 lastLoginAt（兼容旧数据）
            LocalDateTime activityTime = user.getLastActivityAt();
            if (activityTime == null) {
                activityTime = user.getLastLoginAt();
            }
            
            if (activityTime != null && activityTime.isAfter(fiveMinutesAgo)) {
                onlineCount++;
            }
        }
        
        return onlineCount;
    }

    /**
     * 修改用户邀请码
     */
    @Transactional
    public void updateInviteCode(Long userId, String newInviteCode) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        
        // 检查邀请码是否已被其他用户使用
        if (userAccountRepository.findByMyInviteCode(newInviteCode).isPresent()) {
            UserAccount existingUser = userAccountRepository.findByMyInviteCode(newInviteCode).get();
            if (!existingUser.getId().equals(userId)) {
                throw new IllegalArgumentException("邀请码已被其他用户使用");
            }
        }
        
        user.setMyInviteCode(newInviteCode);
        user.setUpdatedAt(LocalDateTime.now());
        userAccountRepository.save(user);
    }

    /**
     * 更新用户备注（用于代理管理）
     */
    @Transactional
    public void updateRemark(Long userId, String remark) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        
        user.setRemark(remark);
        user.setUpdatedAt(LocalDateTime.now());
        userAccountRepository.save(user);
    }
    
    /**
     * 批量更新所有用户的IP地区信息（精确到市级别）
     * 只更新那些地区信息不够详细的用户（如"中国"、"中国大陆"、"海外"等）
     */
    @Transactional
    public int batchUpdateIpRegions() {
        List<UserAccount> allUsers = userAccountRepository.findAll();
        int updatedCount = 0;
        
        for (UserAccount user : allUsers) {
            String ip = user.getLastLoginIp();
            String currentRegion = user.getLastLoginRegion();
            
            // 只更新那些地区信息不够详细的用户
            if (ip != null && !ip.isEmpty() && !ip.equals("unknown") && 
                currentRegion != null && (
                    currentRegion.equals("中国") || 
                    currentRegion.equals("中国大陆") || 
                    currentRegion.equals("海外") ||
                    currentRegion.equals("未知地区")
                )) {
                
                try {
                    // 清空缓存，强制重新查询
                    com.gtcfesk.exchange.utils.IpUtils.clearCache(ip);
                    String detailedRegion = com.gtcfesk.exchange.utils.IpUtils.getRegionByIp(ip);
                    
                    // 如果获取到更详细的地区信息，更新数据库
                    if (detailedRegion != null && !detailedRegion.isEmpty() && 
                        !detailedRegion.equals("未知地区") &&
                        !detailedRegion.equals(currentRegion) &&
                        !detailedRegion.equals("中国") && 
                        !detailedRegion.equals("中国大陆") && 
                        !detailedRegion.equals("海外")) {
                        
                        user.setLastLoginRegion(detailedRegion);
                        user.setUpdatedAt(LocalDateTime.now());
                        userAccountRepository.save(user);
                        updatedCount++;
                        
                        System.out.println("[AdminUserService] 更新用户 " + user.getId() + " (" + user.getEmail() + ") IP地区: " + 
                            currentRegion + " -> " + detailedRegion);
                        
                        // 避免API频率限制，每更新一个用户后延迟2秒
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[AdminUserService] 更新用户 " + user.getId() + " IP地区失败: " + e.getMessage());
                    // 继续处理下一个用户
                }
            }
        }
        
        return updatedCount;
    }
}

