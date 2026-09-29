package com.gtcfesk.exchange.demo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
public interface DemoLedgerRepository extends JpaRepository<DemoLedger, String> {
    Page<DemoLedger> findByUserId(Long userId, Pageable page);
}
