package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.LoanSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoanSettingRepository extends JpaRepository<LoanSetting, Long> {
    List<LoanSetting> findByEnabledTrueOrderByDaysAsc();
}



