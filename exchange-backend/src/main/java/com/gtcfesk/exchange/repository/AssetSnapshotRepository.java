package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.AssetSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetSnapshotRepository extends com.gtcfesk.exchange.tenant.TenantRepository<AssetSnapshot, Long> {}
