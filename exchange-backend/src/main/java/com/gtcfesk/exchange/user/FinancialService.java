package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
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
    
    private final FinancialProductRepository productRepository;
    private final FinancialOrderRepository orderRepository;
    private final AssetAccountRepository assetAccountRepository;
    
    /**
     * 获取启用的理财产品列表
     */
    public List<FinancialProduct> getAvailableProducts() {
        return productRepository.findByEnabledTrueOrderBySortOrderAsc();
    }
    
    /**
     * 获取产品详情
     */
    public FinancialProduct getProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException("产品不存在"));
    }
    
    /**
     * 申购理财产品
     */
    @Transactional
    public FinancialOrder purchaseProduct(Long userId, Long productId, BigDecimal purchaseAmount) {
        com.gtcfesk.exchange.common.TradeValidation.positive(purchaseAmount, "申购金额");
        // 获取产品
        FinancialProduct product = getProduct(productId);
        
        if (!product.getEnabled()) {
            throw new BusinessException("产品已下架");
        }
        
        // 验证申购金额
        if (purchaseAmount.compareTo(product.getMinPurchase()) < 0) {
            throw new BusinessException("申购金额不能小于" + product.getMinPurchase());
        }
        if (purchaseAmount.compareTo(product.getMaxPurchase()) > 0) {
            throw new BusinessException("申购金额不能大于" + product.getMaxPurchase());
        }
        
        // 检查用户资金账户余额
        AssetAccount fundAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "FUND")
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
        
        // 计算收益
        BigDecimal dailyYield = purchaseAmount.multiply(product.getDailyYieldRate())
                .divide(new BigDecimal("100"), 16, RoundingMode.HALF_UP);
        BigDecimal totalYield = dailyYield.multiply(new BigDecimal(product.getTermDays()));
        
        // 创建订单
        FinancialOrder order = new FinancialOrder();
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
        order.setPurchaseTime(LocalDateTime.now());
        order.setEndTime(LocalDateTime.now().plusDays(product.getTermDays()));
        
        return orderRepository.save(order);
    }
    
    /**
     * 违约赎回
     */
    @Transactional
    public FinancialOrder earlyRedeem(Long userId, Long orderId) {
        FinancialOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此订单");
        }
        
        if (!"IN_PROGRESS".equals(order.getStatus())) {
            throw new BusinessException("订单状态不允许赎回");
        }
        
        // 计算违约金
        BigDecimal penaltyAmount = order.getPurchaseAmount()
                .multiply(order.getPenaltyRate())
                .divide(new BigDecimal("100"), 16, RoundingMode.HALF_UP);
        
        // 计算实际返还金额（本金 - 违约金）
        BigDecimal refundAmount = order.getPurchaseAmount().subtract(penaltyAmount);
        
        // 解冻并扣除资金
        AssetAccount fundAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "FUND")
                .orElseThrow(() -> new BusinessException("资金账户不存在"));
        
        BigDecimal frozen = fundAccount.getFrozen() != null ? fundAccount.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(order.getPurchaseAmount()) < 0) {
            throw new BusinessException("冻结资金异常");
        }
        
        // 解冻全部本金，但只返还扣除违约金后的金额
        fundAccount.setFrozen(frozen.subtract(order.getPurchaseAmount()));
        BigDecimal available = fundAccount.getAvailable() != null ? fundAccount.getAvailable() : BigDecimal.ZERO;
        fundAccount.setAvailable(available.add(refundAmount));
        assetAccountRepository.save(fundAccount);
        
        // 更新订单状态
        order.setStatus("REDEEMED");
        order.setPenaltyAmount(penaltyAmount);
        order.setRedeemTime(LocalDateTime.now());
        
        return orderRepository.save(order);
    }
    
    /**
     * 获取用户的订单列表
     */
    public List<FinancialOrder> getUserOrders(Long userId) {
        return orderRepository.findByUserIdOrderByPurchaseTimeDesc(userId);
    }
    
    /**
     * 获取用户的订单（按状态）
     */
    public List<FinancialOrder> getUserOrdersByStatus(Long userId, String status) {
        return orderRepository.findByUserIdAndStatusOrderByPurchaseTimeDesc(userId, status);
    }
    
    /**
     * 计算违约赎回的违约金
     */
    public BigDecimal calculatePenalty(Long userId, Long orderId) {
        FinancialOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        
        if (!order.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该订单");
        }
        return order.getPurchaseAmount()
                .multiply(order.getPenaltyRate())
                .divide(new BigDecimal("100"), 16, RoundingMode.HALF_UP);
    }
}



