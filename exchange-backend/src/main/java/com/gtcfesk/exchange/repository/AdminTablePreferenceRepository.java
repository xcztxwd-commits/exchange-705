package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.AdminTablePreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminTablePreferenceRepository extends com.gtcfesk.exchange.tenant.TenantRepository<AdminTablePreference, String> {}
