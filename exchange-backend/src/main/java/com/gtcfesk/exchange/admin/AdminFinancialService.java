package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.FinancialOrder;
import com.gtcfesk.exchange.entity.FinancialProduct;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.FinancialOrderRepository;
import com.gtcfesk.exchange.repository.FinancialProductRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdminFinancialService {
    
    private final FinancialProductRepository productRepository;
    private final FinancialOrderRepository orderRepository;
    private final UserAccountRepository userAccountRepository;
    
    /**
     * 获取所有理财产品
     */
    public List<FinancialProduct> getAllProducts() {
        return productRepository.findAll();
    }
    
    /**
     * 创建理财产品
     */
    @Transactional
    public FinancialProduct createProduct(FinancialProduct product) {
        return productRepository.save(product);
    }
    
    /**
     * 更新理财产品
     */
    @Transactional
    public FinancialProduct updateProduct(FinancialProduct product) {
        FinancialProduct existing = productRepository.findById(product.getId())
                .orElseThrow(() -> new BusinessException("产品不存在"));
        
        existing.setName(product.getName());
        existing.setImageUrl(product.getImageUrl());
        existing.setCurrency(product.getCurrency());
        existing.setDailyYieldRate(product.getDailyYieldRate());
        existing.setRentalFee(product.getRentalFee());
        existing.setMinPurchase(product.getMinPurchase());
        existing.setMaxPurchase(product.getMaxPurchase());
        existing.setTermDays(product.getTermDays());
        existing.setPenaltyRate(product.getPenaltyRate());
        existing.setDescription(product.getDescription());
        existing.setEnabled(product.getEnabled());
        existing.setSortOrder(product.getSortOrder());
        
        return productRepository.save(existing);
    }
    
    /**
     * 删除理财产品
     */
    @Transactional
    public void deleteProduct(Long id) {
        // 检查是否有订单
        List<FinancialOrder> orders = orderRepository.findByProductId(id);
        if (!orders.isEmpty()) {
            throw new BusinessException("该产品已有订单，无法删除");
        }
        productRepository.deleteById(id);
    }
    
    /**
     * 获取所有订单
     */
    public List<FinancialOrder> getOrders(String status, Long userId, String userEmail) {
        List<FinancialOrder> orders;
        if (status != null && !status.isEmpty()) {
            orders = orderRepository.findByStatusOrderByPurchaseTimeDesc(status);
        } else {
            orders = orderRepository.findAllByOrderByPurchaseTimeDesc();
        }
        
        Long agent = com.gtcfesk.exchange.config.BackendAccess.agentId();
        if (agent != null) {
            java.util.Set<Long> allowed = userAccountRepository.findByParentUserId(agent).stream().map(UserAccount::getId).collect(java.util.stream.Collectors.toSet());
            orders = orders.stream().filter(o -> allowed.contains(o.getUserId())).collect(java.util.stream.Collectors.toList());
        }
        // 按用户ID过滤
        if (userId != null) {
            orders = orders.stream()
                    .filter(order -> order.getUserId() != null && order.getUserId().equals(userId))
                    .collect(java.util.stream.Collectors.toList());
        }
        
        // 按用户邮箱过滤
        if (userEmail != null && !userEmail.trim().isEmpty()) {
            Optional<UserAccount> userOpt = userAccountRepository.findByEmail(userEmail.trim());
            if (userOpt.isPresent()) {
                Long targetUserId = userOpt.get().getId();
                orders = orders.stream()
                        .filter(order -> order.getUserId() != null && order.getUserId().equals(targetUserId))
                        .collect(java.util.stream.Collectors.toList());
            } else {
                // 用户不存在，返回空列表
                orders = new java.util.ArrayList<>();
            }
        }
        
        return orders;
    }
}

