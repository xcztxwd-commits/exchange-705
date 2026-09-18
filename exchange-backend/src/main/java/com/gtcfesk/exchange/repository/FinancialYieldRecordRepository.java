package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.FinancialYieldRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface FinancialYieldRecordRepository extends JpaRepository<FinancialYieldRecord, Long> {
    List<FinancialYieldRecord> findByOrderIdOrderByYieldDateDesc(Long orderId);
    List<FinancialYieldRecord> findByUserIdOrderByYieldDateDesc(Long userId);
    List<FinancialYieldRecord> findByOrderIdAndStatusOrderByYieldDateDesc(Long orderId, String status);
    FinancialYieldRecord findByOrderIdAndYieldDate(Long orderId, LocalDate yieldDate);
    List<FinancialYieldRecord> findByStatusOrderByYieldDateAsc(String status);
}



