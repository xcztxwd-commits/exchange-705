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
public interface OptionOrderRepository extends JpaRepository<OptionOrder, Long> {
    
    List<OptionOrder> findByUserIdOrderByCreatedAtDesc(Long userId);
    
    List<OptionOrder> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
    
    Page<OptionOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    
    Page<OptionOrder> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status, Pageable pageable);
    
    List<OptionOrder> findByStatus(String status);
    
    // 后台管理查询
    Page<OptionOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);
    
    @Query("SELECT SUM(COALESCE(o.amount, 0)) FROM OptionOrder o WHERE o.createdAt >= :start AND o.createdAt <= :end")
    java.math.BigDecimal sumAmountByCreatedAtBetween(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );
}



