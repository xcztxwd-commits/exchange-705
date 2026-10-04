package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.OrderRequest;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.FinancialOrder;
import com.gtcfesk.exchange.entity.FinancialProduct;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.FinancialOrderRepository;
import com.gtcfesk.exchange.repository.FinancialProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FinancialService {
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService audit;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.repository.UserAccountRepository users;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;
    
    private final FinancialProductRepository productRepository;
    private final FinancialOrderRepository orderRepository;
    private final AssetAccountRepository assetAccountRepository;
    
    /**
     * 获取启用的理财产品列表
     */
    public List<FinancialProduct> getAvailableProducts() {
        return productRepository.findByTenantIdAndEnabledTrueOrderBySortOrderAsc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
    }
    
    /**
     * 获取产品详情
     */
    public FinancialProduct getProduct(Long productId) {
        return productRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), productId)
                .orElseThrow(() -> new BusinessException("产品不存在"));
    }
    
    /**
     * 申购理财产品
     */
    @Transactional
    public FinancialOrder purchaseProduct(Long userId, Long productId, BigDecimal purchaseAmount) {
        return purchaseProduct(userId,productId,purchaseAmount,null);
    }

    @Transactional
    public FinancialOrder purchaseProduct(Long userId, Long productId, BigDecimal purchaseAmount, String key) {
        String requestKey=OrderRequest.optional(key);
        String requestHash=requestKey==null?null:OrderRequest.hash("financial",productId,purchaseAmount);
        tenantPolicy.requireNewBusiness("financial");
        com.gtcfesk.exchange.common.TradeValidation.positive(purchaseAmount, "申购金额");
        // 获取产品
        FinancialProduct product = getProduct(productId);
        
        validateProduct(product);
        if (!Boolean.TRUE.equals(product.getEnabled())) {
            throw new BusinessException("产品已下架");
        }
        
        // 验证申购金额
        if (purchaseAmount.compareTo(product.getMinPurchase()) < 0) {
            throw new BusinessException("申购金额不能小于" + product.getMinPurchase());
        }
        if (purchaseAmount.compareTo(product.getMaxPurchase()) > 0) {
            throw new BusinessException("申购金额不能大于" + product.getMaxPurchase());
        }
        
        FinancialCurrentLocks.user(entityManager,userId);
        com.gtcfesk.exchange.entity.UserAccount owner = users.lockById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        com.gtcfesk.exchange.tenant.TenantContext.require(owner.getTenantId());
        Long agent = com.gtcfesk.exchange.config.BackendAccess.agentId();
        if (agent != null && !agent.equals(owner.getParentUserId()))
            throw new org.springframework.security.access.AccessDeniedException("无权操作此用户理财");

        FinancialCurrentLocks.assets(entityManager,userId);
        List<AssetAccount> lockedAccounts = assetAccountRepository.lockByUserId(userId);
        // A missing request key must not take an RR gap lock: unrelated first purchases can deadlock.
        if(requestKey!=null) {
            FinancialOrder previous=entityManager==null
                    ? orderRepository.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId,requestKey).orElse(null)
                    : FinancialCurrentLocks.request(entityManager,userId,requestKey);
            if(previous!=null) return replay(previous,userId,requestKey,requestHash);
        }
        // 计算收益
        BigDecimal dailyYield = purchaseAmount.multiply(product.getDailyYieldRate())
                .divide(new BigDecimal("100"), 16, RoundingMode.HALF_UP);
        BigDecimal totalYield = dailyYield.multiply(new BigDecimal(product.getTermDays()));
        
        // 创建订单
        FinancialOrder order = new FinancialOrder();
        order.setRequestKey(requestKey);order.setRequestHash(requestHash);
        order.setUserId(userId);
        order.setProductId(productId);
        order.setProductName(product.getName());
        order.setPurchaseAmount(purchaseAmount);
        order.setCurrency(product.getCurrency());
        order.setDailyYieldRate(product.getDailyYieldRate());
        order.setDailyYield(dailyYield);
        order.setTotalYield(totalYield);
        order.setTermDays(product.getTermDays());
        order.setPenaltyRate(product.getPenaltyRate());
        order.setStatus("IN_PROGRESS");
        LocalDateTime purchasedAt = LocalDateTime.now();
        order.setPurchaseTime(purchasedAt);
        order.setEndTime(purchasedAt.plusDays(product.getTermDays()));
        order.setLastAccruedDate(purchasedAt.toLocalDate().minusDays(1));
        order.setAccruedYield(BigDecimal.ZERO);

        if(requestKey!=null && entityManager!=null) {
            // INSERT arbitrates the unique key without locking an absent range. Only the exact
            // request-constraint duplicate is a replay; every other SQL error aborts this transaction.
            if(!FinancialCurrentLocks.insertRequest(entityManager,order))
                return replay(FinancialCurrentLocks.committedRequest(entityManager,userId,requestKey),userId,requestKey,requestHash);
            FinancialCurrentLocks.order(entityManager,order.getId());
            order=orderRepository.lockById(order.getId()).orElseThrow(() -> new IllegalStateException("Financial request insert has no current row"));
        }
        AssetAccount fundAccount = lockedAccounts.stream().filter(a -> "FUND".equals(a.getCoin())).findFirst()
                .orElseGet(() -> {
                    AssetAccount account = new AssetAccount();
                    account.setUserId(userId);
                    account.setCoin("FUND");
                    account.setAvailable(BigDecimal.ZERO);
                    account.setFrozen(BigDecimal.ZERO);
                    return assetAccountRepository.save(account);
                });

        BigDecimal available = fundAccount.getAvailable() != null ? fundAccount.getAvailable() : BigDecimal.ZERO;
        if (available.compareTo(purchaseAmount) < 0) {
            throw new BusinessException("资金账户余额不足");
        }

        // 冻结资金
        fundAccount.setAvailable(available.subtract(purchaseAmount));
        BigDecimal frozen = fundAccount.getFrozen() != null ? fundAccount.getFrozen() : BigDecimal.ZERO;
        fundAccount.setFrozen(frozen.add(purchaseAmount));
        assetAccountRepository.save(fundAccount);
        
        orderRepository.save(order);
        recordSuccess("FINANCIAL_PURCHASE",order.getId().toString(),"userId="+userId+"; amount="+purchaseAmount+"; availableBefore="+available+"; availableAfter="+fundAccount.getAvailable()+"; frozenBefore="+frozen+"; frozenAfter="+fundAccount.getFrozen(),null);
        return order;
    }
    
    private FinancialOrder replay(FinancialOrder previous,Long userId,String requestKey,String requestHash) {
        com.gtcfesk.exchange.tenant.TenantContext.require(previous.getTenantId());
        if(!userId.equals(previous.getUserId()) || !requestKey.equals(previous.getRequestKey()))
            throw new IllegalStateException("Financial request receipt owner/key mismatch");
        OrderRequest.same(previous.getRequestHash(),requestHash);
        return previous;
    }
    private void recordSuccess(String action, String object, String detail, String reason) {
        if (com.gtcfesk.exchange.control.ControlIdentity.current()!=null)
            audit.recordCurrent(action, object, detail, reason);
        else
            audit.record(null, com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), null, action, object, "SUCCESS", detail, reason);
    }
    /** Validate the selected product before locking or freezing funds; readiness checks only one product. */
    static void validateProduct(FinancialProduct product) {
        if (product.getTermDays() == null || product.getTermDays() <= 0 || !"USD".equals(product.getCurrency()))
            throw new BusinessException("理财期限或资金账户币种配置异常");
        com.gtcfesk.exchange.common.TradeValidation.positive(product.getMinPurchase(), "最小申购金额");
        com.gtcfesk.exchange.common.TradeValidation.positive(product.getMaxPurchase(), "最大申购金额");
        com.gtcfesk.exchange.common.TradeValidation.nonNegative(product.getDailyYieldRate(), "理财收益率");
        com.gtcfesk.exchange.common.TradeValidation.nonNegative(product.getRentalFee(), "租金");
        com.gtcfesk.exchange.common.TradeValidation.nonNegative(product.getPenaltyRate(), "赎回费率");
        if (product.getMaxPurchase().compareTo(product.getMinPurchase()) < 0 || product.getPenaltyRate().compareTo(new BigDecimal("100")) > 0)
            throw new BusinessException("理财金额范围或赎回费率配置异常");
    }

    /**
     * 违约赎回
     */
    @Transactional
    public FinancialOrder earlyRedeem(Long userId, Long orderId) {
        FinancialCurrentLocks.user(entityManager,userId);
        com.gtcfesk.exchange.entity.UserAccount owner = users.lockById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        com.gtcfesk.exchange.tenant.TenantContext.require(owner.getTenantId());
        Long agent = com.gtcfesk.exchange.config.BackendAccess.agentId();
        if (agent != null && !agent.equals(owner.getParentUserId()))
            throw new org.springframework.security.access.AccessDeniedException("无权操作此订单");
        FinancialCurrentLocks.assets(entityManager,userId);
        List<AssetAccount> accounts = assetAccountRepository.lockByUserId(userId);
        FinancialCurrentLocks.order(entityManager,orderId);
        FinancialOrder order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        com.gtcfesk.exchange.tenant.TenantContext.require(order.getTenantId());
        if (!userId.equals(order.getUserId())) throw new BusinessException("无权操作此订单");
        if ("REDEEMED".equals(order.getStatus()) && order.getRedeemTime()!=null) return order;
        if (!"IN_PROGRESS".equals(order.getStatus())) throw new BusinessException("订单状态不允许赎回");

        com.gtcfesk.exchange.common.TradeValidation.positive(order.getPurchaseAmount(), "申购本金");
        if(order.getPenaltyRate()==null||order.getPenaltyRate().signum()<0||order.getPenaltyRate().compareTo(new BigDecimal("100"))>0)
            throw new BusinessException("赎回费率配置异常");
        BigDecimal penaltyAmount = order.getPurchaseAmount().multiply(order.getPenaltyRate())
                .divide(new BigDecimal("100"), 16, RoundingMode.HALF_UP);
        BigDecimal refundAmount = order.getPurchaseAmount().subtract(penaltyAmount);
        AssetAccount fundAccount = accounts.stream().filter(a -> "FUND".equals(a.getCoin())).findFirst()
                .orElseThrow(() -> new BusinessException("资金账户不存在"));
        BigDecimal frozen = fundAccount.getFrozen() != null ? fundAccount.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(order.getPurchaseAmount()) < 0) throw new BusinessException("冻结资金异常");
        BigDecimal available = fundAccount.getAvailable() != null ? fundAccount.getAvailable() : BigDecimal.ZERO;
        fundAccount.setFrozen(frozen.subtract(order.getPurchaseAmount()));
        fundAccount.setAvailable(available.add(refundAmount));
        assetAccountRepository.save(fundAccount);
        order.setStatus("REDEEMED");
        order.setPenaltyAmount(penaltyAmount);
        order.setRedeemTime(LocalDateTime.now());
        orderRepository.save(order);
        recordSuccess("FINANCIAL_REDEEM",orderId.toString(),"userId="+userId+"; refund="+refundAmount+"; penalty="+penaltyAmount+"; availableBefore="+available+"; availableAfter="+fundAccount.getAvailable()+"; frozenBefore="+frozen+"; frozenAfter="+fundAccount.getFrozen()+"; status=IN_PROGRESS/REDEEMED",null);
        return order;
    }
    /**
     * 获取用户的订单列表
     */
    public List<FinancialOrder> getUserOrders(Long userId) {
        return orderRepository.findByTenantIdAndUserIdOrderByPurchaseTimeDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId);
    }
    
    /**
     * 获取用户的订单（按状态）
     */
    public List<FinancialOrder> getUserOrdersByStatus(Long userId, String status) {
        return orderRepository.findByTenantIdAndUserIdAndStatusOrderByPurchaseTimeDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, status);
    }
    
    /**
     * 计算违约赎回的违约金
     */
    public BigDecimal calculatePenalty(Long userId, Long orderId) {
        FinancialOrder order = orderRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        
        if (!order.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该订单");
        }
        return order.getPurchaseAmount()
                .multiply(order.getPenaltyRate())
                .divide(new BigDecimal("100"), 16, RoundingMode.HALF_UP);
    }
}
