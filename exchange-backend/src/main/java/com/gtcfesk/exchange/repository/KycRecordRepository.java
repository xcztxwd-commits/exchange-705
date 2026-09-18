package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.KycRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KycRecordRepository extends JpaRepository<KycRecord, Long>, JpaSpecificationExecutor<KycRecord> {
    Optional<KycRecord> findByUserId(Long userId);
    
    Optional<KycRecord> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
    
    List<KycRecord> findByStatus(String status);
    
    List<KycRecord> findByStatusOrderByCreatedAtDesc(String status);
    
    Page<KycRecord> findByStatus(String status, Pageable pageable);
}

