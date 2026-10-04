package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.OptionOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OptionOrderRepository extends com.gtcfesk.exchange.tenant.TenantRepository<OptionOrder, Long> {
    interface ExpiryCandidate {
        Long getId();
        Long getUserId();
        LocalDateTime getOpenTime();
        Integer getDuration();
    }
    // Keyset projection: routing only. The atomic close refreshes all state after account locks.
    @Query("SELECT o.id AS id, o.userId AS userId, o.openTime AS openTime, o.duration AS duration FROM OptionOrder o WHERE o.tenantId=:tenant AND o.status='TRADING' AND o.id>:after ORDER BY o.id")
    List<ExpiryCandidate> expiryCandidates(@Param("tenant") Long tenant, @Param("after") long after, Pageable page);

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OptionOrder o WHERE o.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND o.id=:id")
    java.util.Optional<OptionOrder> lockById(@Param("id") Long id);

    // Current read after the caller locks the user, including under MySQL REPEATABLE_READ.
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_READ)
    java.util.Optional<OptionOrder> findByTenantIdAndUserIdAndRequestKey(Long tenantId, Long userId, String requestKey);


    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE OptionOrder o SET o.deletedAt = :deletedAt, o.deletedBy = :deletedBy, o.rowVersion = o.rowVersion + 1 WHERE o.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (o.id = :id AND o.rowVersion = :version)")
    int updateDeletion(@Param("id") Long id, @Param("version") long version,
                       @Param("deletedAt") LocalDateTime deletedAt, @Param("deletedBy") String deletedBy);

    List<OptionOrder> findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long tenantId, Long userId);
    List<OptionOrder> findByTenantIdAndUserIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(Long tenantId, Long userId, String status);
    
    List<OptionOrder> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId);
    
    List<OptionOrder> findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(Long tenantId, Long userId, String status);
    
    Page<OptionOrder> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId, Pageable pageable);
    
    Page<OptionOrder> findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(Long tenantId, Long userId, String status, Pageable pageable);
    
    List<OptionOrder> findByTenantIdAndStatus(Long tenantId, String status);
    
    // 后台管理查询
    Page<OptionOrder> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);
    
    @org.springframework.data.jpa.repository.Query("SELECT SUM(COALESCE(o.amount, 0)) FROM OptionOrder o WHERE o.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (o.createdAt >= :start AND o.createdAt <= :end)")
    java.math.BigDecimal sumAmountByCreatedAtBetween(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );
}



