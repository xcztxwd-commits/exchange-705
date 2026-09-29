package com.gtcfesk.exchange.demo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
import java.util.*;
public interface DemoOrderRepository extends JpaRepository<DemoOrder, String> {
    Optional<DemoOrder> findByUserIdAndRequestKey(Long userId, String key);
    Optional<DemoOrder> findByIdAndUserId(String id, Long userId);
    List<DemoOrder> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
    Page<DemoOrder> findByUserIdAndStatus(Long userId, String status, Pageable page);
}
