package com.gtcfesk.exchange.activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
public interface ActivityMaterialRepository extends JpaRepository<ActivityMaterial,Long>, JpaSpecificationExecutor<ActivityMaterial> {}
