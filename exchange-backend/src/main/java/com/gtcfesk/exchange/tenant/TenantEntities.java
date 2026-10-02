package com.gtcfesk.exchange.tenant;

import javax.persistence.*;
import javax.persistence.criteria.*;
import javax.persistence.metamodel.*;
import java.util.List;

/** Criteria lookup for the few audited EntityManager paths, never EntityManager.find(). */
public final class TenantEntities {
    private TenantEntities() {}
    public static <T extends TenantOwnedEntity> T find(EntityManager em, Class<T> type, Object id) {
        return find(em, type, id, LockModeType.NONE);
    }
    public static <T extends TenantOwnedEntity> T find(EntityManager em, Class<T> type, Object id, LockModeType lock) {
        Long tenant = TenantContext.requireTenantId();
        EntityType<T> metadata = em.getMetamodel().entity(type);
        SingularAttribute<? super T, ?> key = metadata.getSingularAttributes().stream()
                .filter(SingularAttribute::isId).findFirst().orElseThrow(() -> new IllegalArgumentException("缺少主键"));
        CriteriaBuilder builder = em.getCriteriaBuilder();
        CriteriaQuery<T> query = builder.createQuery(type);
        Root<T> root = query.from(type);
        query.select(root).where(builder.equal(root.get("tenantId"), tenant), builder.equal(root.get(key.getName()), id));
        List<T> rows = em.createQuery(query).setLockMode(lock).setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }
}
