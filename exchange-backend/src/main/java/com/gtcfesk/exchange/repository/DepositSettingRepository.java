package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.DepositSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DepositSettingRepository extends JpaRepository<DepositSetting, Long> {
    Optional<DepositSetting> findByNetwork(String network);
    Optional<DepositSetting> findByNetworkAndType(String network, String type);
    List<DepositSetting> findByTypeAndEnabled(String type, Boolean enabled);
}

