package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.FinancialProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FinancialProductRepository extends com.gtcfesk.exchange.tenant.TenantRepository<FinancialProduct, Long> {
    List<FinancialProduct> findByTenantIdAndEnabledTrueOrderBySortOrderAsc(Long tenantId);
}



