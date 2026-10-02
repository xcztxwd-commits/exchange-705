package com.gtcfesk.exchange.activity;
public interface ActivityMaterialRepository extends com.gtcfesk.exchange.tenant.TenantRepository<ActivityMaterial,Long> {
 @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select m from ActivityMaterial m where m.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} and m.id=:id")
 java.util.Optional<ActivityMaterial> lock(@org.springframework.data.repository.query.Param("id") Long id);
}
