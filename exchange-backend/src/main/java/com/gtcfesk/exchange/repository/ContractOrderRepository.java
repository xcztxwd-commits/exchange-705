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

    List<ContractOrder> findByStatusAndTypeAndLimitMatchEnabledTrue(String status, String type);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE ContractOrder c SET c.status = 'OPEN', c.openPrice = :marketPrice, c.currentPrice = :marketPrice, " +
            "c.openTime = :now, c.updatedAt = :now, c.rowVersion = c.rowVersion + 1 " +
            "WHERE c.id = :id AND c.rowVersion = :version AND c.status = 'PENDING' AND c.type = 'LIMIT' " +
            "AND c.limitMatchEnabled = true AND c.price > 0 AND " +
            "((c.side = 'BUY' AND :marketPrice <= c.price) OR (c.side = 'SELL' AND :marketPrice >= c.price))")
    int openPendingLimitOrder(@Param("id") Long id, @Param("version") long version,
                              @Param("marketPrice") java.math.BigDecimal marketPrice, @Param("now") LocalDateTime now);
    
    // 根据交易对和状态查找订单
    List<ContractOrder> findBySymbolAndStatus(String symbol, String status);
}
