package com.gtcfesk.exchange.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import javax.persistence.*;

@MappedSuperclass
public abstract class TenantOwnedEntity {
    @JsonIgnore
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;
    @JsonIgnore public Long getTenantId() { return tenantId; }
    @JsonIgnore public void setTenantId(Long id) {
        TenantContext.require(id);
        if (tenantId != null && !tenantId.equals(id)) throw new IllegalArgumentException("不可修改数据租户");
        tenantId = id;
    }
    @PrePersist public void assignTenantBeforeInsert() {
        if (tenantId == null) tenantId = TenantContext.requireTenantId();
        TenantContext.require(tenantId);
    }
    @PostLoad @PreUpdate @PreRemove public void verifyTenantOwner() { TenantContext.require(tenantId); }
}
