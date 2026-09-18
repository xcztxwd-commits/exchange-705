package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.LoanPersonalInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoanPersonalInfoRepository extends JpaRepository<LoanPersonalInfo, Long> {
    Optional<LoanPersonalInfo> findByUserId(Long userId);
    
    List<LoanPersonalInfo> findByStatusOrderByCreatedAtDesc(String status);
    
    List<LoanPersonalInfo> findAllByOrderByCreatedAtDesc();
}



