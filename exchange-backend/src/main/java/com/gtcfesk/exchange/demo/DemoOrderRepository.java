package com.gtcfesk.exchange.demo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
import java.util.*;
public interface DemoOrderRepository extends com.gtcfesk.exchange.tenant.TenantRepository<DemoOrder, String> {
    Optional<DemoOrder> findByTenantIdAndUserIdAndRequestKey(Long tenantId, Long userId, String key);
    Optional<DemoOrder> findByTenantIdAndIdAndUserId(Long tenantId, String id, Long userId);
    List<DemoOrder> findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(Long tenantId, Long userId, String status);
    Page<DemoOrder> findByTenantIdAndUserIdAndStatus(Long tenantId, Long userId, String status, Pageable page);
}
