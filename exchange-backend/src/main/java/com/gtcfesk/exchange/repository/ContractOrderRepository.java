package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.ContractOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ContractOrderRepository extends JpaRepository<ContractOrder, Long> {
    
    List<ContractOrder> findByUserIdOrderByCreatedAtDesc(Long userId);
    
    List<ContractOrder> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
    
    Page<ContractOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    
    Page<ContractOrder> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status, Pageable pageable);
    
    // 后台管理查询
    Page<ContractOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);
    
    @Query("SELECT SUM(COALESCE(c.margin, 0)) FROM ContractOrder c WHERE c.createdAt >= :start AND c.createdAt <= :end")
    java.math.BigDecimal sumMarginByCreatedAtBetween(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );
    
    // 查找所有持仓中的订单
    List<ContractOrder> findByStatus(String status);
    
    // 根据交易对和状态查找订单
    List<ContractOrder> findBySymbolAndStatus(String symbol, String status);
}

