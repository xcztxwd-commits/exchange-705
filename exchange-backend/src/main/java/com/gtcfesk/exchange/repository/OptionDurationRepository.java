package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.OptionDuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OptionDurationRepository extends JpaRepository<OptionDuration, Long> {
    
    List<OptionDuration> findByEnabledTrueOrderBySortOrderAsc();
    
    List<OptionDuration> findAllByOrderBySortOrderAsc();
    
    java.util.Optional<OptionDuration> findByDuration(Integer duration);
}

