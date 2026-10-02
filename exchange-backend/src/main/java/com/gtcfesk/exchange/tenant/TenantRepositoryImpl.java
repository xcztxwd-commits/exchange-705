package com.gtcfesk.exchange.tenant;

import org.springframework.data.jpa.repository.support.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.*;
import org.springframework.transaction.annotation.Transactional;
import javax.persistence.EntityManager;
import java.util.*;

/** Explicit SQL predicates for every inherited operation; entity callbacks are only defence in depth. */
@Transactional(readOnly = true)
public class TenantRepositoryImpl<T extends TenantOwnedEntity, ID> extends SimpleJpaRepository<T, ID>
        implements TenantRepository<T, ID> {
    private final JpaEntityInformation<T, ?> info;
    private final EntityManager em;
    public TenantRepositoryImpl(JpaEntityInformation<T, ?> info, EntityManager em) {
        super(info, em); this.info = info; this.em = em;
    }
    private Specification<T> scope(Long tenant) {
        TenantContext.require(tenant);
        return (root, query, builder) -> builder.equal(root.get("tenantId"), tenant);
    }
    private Specification<T> id(ID id) {
        if (id == null) throw new IllegalArgumentException("对象不存在");
        return (root, query, builder) -> builder.equal(root.get(info.getIdAttribute().getName()), id);
    }
    public Optional<T> findByTenantIdAndId(Long tenant, ID id) { return super.findOne(scope(tenant).and(id(id))); }
    public boolean existsByTenantIdAndId(Long tenant, ID id) { return super.count(scope(tenant).and(id(id))) != 0; }
    public List<T> findAllByTenantId(Long tenant) { return super.findAll(scope(tenant)); }
    public List<T> findAllByTenantId(Long tenant, Sort sort) { return super.findAll(scope(tenant), sort); }
    public Page<T> findAllByTenantId(Long tenant, Pageable page) { return super.findAll(scope(tenant), page); }
    public List<T> findAllByTenantId(Long tenant, Specification<T> specification) { return super.findAll(scope(tenant).and(specification)); }
    public List<T> findAllByTenantId(Long tenant, Specification<T> specification, Sort sort) { return super.findAll(scope(tenant).and(specification), sort); }
    public Page<T> findAllByTenantId(Long tenant, Specification<T> specification, Pageable page) { return super.findAll(scope(tenant).and(specification), page); }
    public List<T> findAllByTenantIdAndIdIn(Long tenant, Iterable<ID> ids) {
        List<ID> values = new ArrayList<>(); ids.forEach(values::add);
        Specification<T> owner = scope(tenant);
        if (values.isEmpty()) return Collections.emptyList();
        return super.findAll(owner.and((root, query, builder) -> root.get(info.getIdAttribute().getName()).in(values)));
    }
    public long countByTenantId(Long tenant) { return super.count(scope(tenant)); }
    public long countByTenantId(Long tenant, Specification<T> specification) { return super.count(scope(tenant).and(specification)); }
    @Override @Transactional public <S extends T> S save(S entity) {
        Long tenant = TenantContext.requireTenantId();
        entity.setTenantId(tenant);
        if (em.contains(entity)) return entity;
        @SuppressWarnings("unchecked") ID key = (ID) info.getId(entity);
        if (key == null || !existsByTenantIdAndId(tenant, key)) {
            // Persist rather than merge: foreign assigned IDs must fail with duplicate-key, never update another tenant.
            em.persist(entity); return entity;
        }
        return em.merge(entity);
    }
    @Override @Transactional public <S extends T> S saveAndFlush(S entity) { S saved = save(entity); em.flush(); return saved; }
    @Override @Transactional public <S extends T> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>(); for (S entity : entities) saved.add(save(entity)); return saved;
    }
    @Override @Transactional public void delete(T entity) {
        TenantContext.require(entity.getTenantId());
        @SuppressWarnings("unchecked") ID key = (ID) info.getId(entity);
        T owned = findByTenantIdAndId(TenantContext.requireTenantId(), key).orElseThrow(() -> new IllegalArgumentException("对象不存在"));
        em.remove(owned);
    }
    @Transactional public void deleteByTenantIdAndId(Long tenant, ID key) {
        T entity = findByTenantIdAndId(tenant, key).orElseThrow(() -> new IllegalArgumentException("对象不存在")); delete(entity);
    }
    @Transactional public void deleteAllByTenantId(Long tenant) { for (T entity : findAllByTenantId(tenant)) delete(entity); }
    @Transactional public void deleteAllByTenantId(Long tenant, Iterable<? extends T> entities) {
        TenantContext.require(tenant);
        for (T entity : entities) TenantContext.require(entity.getTenantId());
        for (T entity : entities) delete(entity);
    }
    @Transactional public void deleteAllByTenantIdAndIdIn(Long tenant, Iterable<ID> ids) {
        List<ID> keys = new ArrayList<>(); ids.forEach(keys::add);
        List<T> entities = findAllByTenantIdAndIdIn(tenant, keys);
        if (entities.size() != new HashSet<>(keys).size()) throw new IllegalArgumentException("对象不存在，未修改任何记录");
        deleteAllByTenantId(tenant, entities);
    }
    @Override @Transactional public void flush() { TenantContext.requireTenantId(); em.flush(); }
}
