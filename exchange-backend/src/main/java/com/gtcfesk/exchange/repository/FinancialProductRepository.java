package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.FinancialProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FinancialProductRepository extends JpaRepository<FinancialProduct, Long> {
    List<FinancialProduct> findByEnabledTrueOrderBySortOrderAsc();
}



