package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.WithdrawRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WithdrawRecordRepository extends JpaRepository<WithdrawRecord, Long> {
    List<WithdrawRecord> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<WithdrawRecord> findByStatusOrderByCreatedAtDesc(String status);
    List<WithdrawRecord> findByStatusAndTypeOrderByCreatedAtDesc(String status, String type);
    List<WithdrawRecord> findAllByOrderByCreatedAtDesc();
    
    @Query("SELECT SUM(w.amount) FROM WithdrawRecord w WHERE (w.status = 'APPROVED' OR w.status = 'COMPLETED') AND w.createdAt >= :start AND w.createdAt <= :end")
    java.math.BigDecimal sumAmountByApprovedStatusAndCreatedAtBetween(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );
}

