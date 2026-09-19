package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.AssetAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetAccountRepository extends JpaRepository<AssetAccount, Long> {

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT a FROM AssetAccount a WHERE a.userId = :userId ORDER BY a.coin, a.id")
    List<AssetAccount> lockByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    List<AssetAccount> findByUserId(Long userId);

    Optional<AssetAccount> findByUserIdAndCoin(Long userId, String coin);
}




