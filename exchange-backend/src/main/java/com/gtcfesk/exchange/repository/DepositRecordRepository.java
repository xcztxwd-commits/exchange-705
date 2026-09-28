package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.DepositRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DepositRecordRepository extends JpaRepository<DepositRecord, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<DepositRecord> {
    java.util.Optional<DepositRecord> findByCreatedByTypeAndCreatedByIdAndIdempotencyKey(String type, Long id, String key);

    List<DepositRecord> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<DepositRecord> findByStatusOrderByCreatedAtDesc(String status);
    
    @Query("SELECT SUM(d.amount) FROM DepositRecord d WHERE (d.source IS NULL OR d.source <> 'ADMIN_MANUAL') AND d.status = :status AND d.createdAt >= :start AND d.createdAt <= :end")
    java.math.BigDecimal sumAmountByStatusAndCreatedAtBetween(
        @Param("status") String status,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );
}



