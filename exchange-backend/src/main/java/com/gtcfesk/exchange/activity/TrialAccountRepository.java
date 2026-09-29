package com.gtcfesk.exchange.activity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TrialAccountRepository extends JpaRepository<TrialAccount,Long>{
 @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select a from TrialAccount a where a.userId=:user")
 java.util.Optional<TrialAccount> lock(@org.springframework.data.repository.query.Param("user") Long user);
}
