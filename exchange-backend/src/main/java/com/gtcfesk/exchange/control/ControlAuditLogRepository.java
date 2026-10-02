package com.gtcfesk.exchange.control;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.*;
public interface ControlAuditLogRepository extends Repository<ControlAuditLog,Long> {
 ControlAuditLog save(ControlAuditLog log);
 Page<ControlAuditLog> findAllByOrderByIdDesc(Pageable page);
 Page<ControlAuditLog> findByTenantIdOrderByIdDesc(Long tenant,Pageable page);
}
