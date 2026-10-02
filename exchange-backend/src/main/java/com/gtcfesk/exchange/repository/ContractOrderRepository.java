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
public interface ContractOrderRepository extends com.gtcfesk.exchange.tenant.TenantRepository<ContractOrder, Long> {
    // Current read after the caller locks the user, including under MySQL REPEATABLE_READ.
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_READ)
    java.util.Optional<ContractOrder> findByTenantIdAndUserIdAndRequestKey(Long tenantId, Long userId, String requestKey);


    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE ContractOrder o SET o.deletedAt = :deletedAt, o.deletedBy = :deletedBy, o.rowVersion = o.rowVersion + 1 WHERE o.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (o.id = :id AND o.rowVersion = :version)")
    int updateDeletion(@Param("id") Long id, @Param("version") long version,
                       @Param("deletedAt") LocalDateTime deletedAt, @Param("deletedBy") String deletedBy);

    List<ContractOrder> findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long tenantId, Long userId);
    List<ContractOrder> findByTenantIdAndUserIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(Long tenantId, Long userId, String status);
    
    List<ContractOrder> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId);
    
    List<ContractOrder> findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(Long tenantId, Long userId, String status);
    
    Page<ContractOrder> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId, Pageable pageable);
    
    Page<ContractOrder> findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(Long tenantId, Long userId, String status, Pageable pageable);
    
    // 后台管理查询
    Page<ContractOrder> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);
    
    @org.springframework.data.jpa.repository.Query("SELECT SUM(COALESCE(c.margin, 0)) FROM ContractOrder c WHERE c.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (c.createdAt >= :start AND c.createdAt <= :end)")
    java.math.BigDecimal sumMarginByCreatedAtBetween(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );
    
    // 查找所有持仓中的订单
    List<ContractOrder> findByTenantIdAndStatus(Long tenantId, String status);

    List<ContractOrder> findByTenantIdAndStatusAndTypeAndLimitMatchEnabledTrue(Long tenantId, String status, String type);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE ContractOrder c SET c.status = 'OPEN', c.openPrice = :marketPrice, c.currentPrice = :marketPrice, c.openTime = :now, c.updatedAt = :now, c.rowVersion = c.rowVersion + 1 WHERE c.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (c.id = :id AND c.rowVersion = :version AND c.status = 'PENDING' AND c.type = 'LIMIT' AND c.limitMatchEnabled = true AND c.price > 0 AND ((c.side = 'BUY' AND :marketPrice <= c.price) OR (c.side = 'SELL' AND :marketPrice >= c.price)))")
    int openPendingLimitOrder(@Param("id") Long id, @Param("version") long version,
                              @Param("marketPrice") java.math.BigDecimal marketPrice, @Param("now") LocalDateTime now);
    
    // 根据交易对和状态查找订单
    List<ContractOrder> findByTenantIdAndSymbolAndStatus(Long tenantId, String symbol, String status);
}
