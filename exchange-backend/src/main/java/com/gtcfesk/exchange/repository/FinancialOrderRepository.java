package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.FinancialOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public interface FinancialOrderRepository extends JpaRepository<FinancialOrder, Long> {
    List<FinancialOrder> findByUserIdOrderByPurchaseTimeDesc(Long userId);
    List<FinancialOrder> findByUserIdAndStatusOrderByPurchaseTimeDesc(Long userId, String status);
    List<FinancialOrder> findAllByOrderByPurchaseTimeDesc();
    List<FinancialOrder> findByStatusOrderByPurchaseTimeDesc(String status);
    List<FinancialOrder> findByProductId(Long productId);
    
    // 统计今日订单数
    long countByPurchaseTimeBetween(LocalDateTime start, LocalDateTime end);
    
    // 查询今日订单
    List<FinancialOrder> findByPurchaseTimeBetween(LocalDateTime start, LocalDateTime end);
    
    // 查询最近有交易的用户ID（去重）
    @Query("SELECT DISTINCT o.userId FROM FinancialOrder o WHERE o.purchaseTime > ?1")
    Set<Long> findDistinctUserIdByPurchaseTimeAfter(LocalDateTime time);
}

