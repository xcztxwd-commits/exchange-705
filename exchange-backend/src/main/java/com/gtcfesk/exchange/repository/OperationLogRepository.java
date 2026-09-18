package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.OperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    
    @Query("SELECT ol FROM OperationLog ol ORDER BY ol.createdAt DESC")
    Page<OperationLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    
    Page<OperationLog> findByAdminIdOrderByCreatedAtDesc(Long adminId, Pageable pageable);
    
    Page<OperationLog> findByOperationTypeOrderByCreatedAtDesc(String operationType, Pageable pageable);
    
    @Query("SELECT ol FROM OperationLog ol WHERE " +
           "(:adminId IS NULL OR ol.adminId = :adminId) AND " +
           "(:operationType IS NULL OR ol.operationType = :operationType) AND " +
           "(:startTime IS NULL OR ol.createdAt >= :startTime) AND " +
           "(:endTime IS NULL OR ol.createdAt <= :endTime) " +
           "ORDER BY ol.createdAt DESC")
    Page<OperationLog> findByConditions(
        @Param("adminId") Long adminId,
        @Param("operationType") String operationType,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime,
        Pageable pageable
    );
}

