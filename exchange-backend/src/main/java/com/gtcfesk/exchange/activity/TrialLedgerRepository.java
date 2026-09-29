package com.gtcfesk.exchange.activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
public interface TrialLedgerRepository extends JpaRepository<TrialLedger,Long>{
 Page<TrialLedger> findByUserIdOrderByIdDesc(Long userId,Pageable page);
}
