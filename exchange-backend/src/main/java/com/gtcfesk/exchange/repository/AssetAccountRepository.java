package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.AssetAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetAccountRepository extends JpaRepository<AssetAccount, Long> {

    List<AssetAccount> findByUserId(Long userId);

    Optional<AssetAccount> findByUserIdAndCoin(Long userId, String coin);
}




