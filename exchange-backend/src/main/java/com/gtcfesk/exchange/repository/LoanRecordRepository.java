package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.LoanRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoanRecordRepository extends JpaRepository<LoanRecord, Long> {
    List<LoanRecord> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<LoanRecord> findAllByOrderByCreatedAtDesc();
}



