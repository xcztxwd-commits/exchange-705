package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.TransferRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransferRecordRepository extends JpaRepository<TransferRecord, Long> {
    List<TransferRecord> findByUserIdOrderByCreatedAtDesc(Long userId);
    
    Page<TransferRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}



