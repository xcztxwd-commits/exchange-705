package com.gtcfesk.exchange.tenant;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.*;
import java.util.*;

/** Deliberately does not expose JpaRepository's unscoped CRUD or specification methods. */
@NoRepositoryBean
public interface TenantRepository<T extends TenantOwnedEntity, ID> extends Repository<T, ID> {
    Optional<T> findByTenantIdAndId(Long tenantId, ID id);
    boolean existsByTenantIdAndId(Long tenantId, ID id);
    List<T> findAllByTenantId(Long tenantId);
    List<T> findAllByTenantId(Long tenantId, Sort sort);
    Page<T> findAllByTenantId(Long tenantId, Pageable page);
    List<T> findAllByTenantId(Long tenantId, Specification<T> specification);
    List<T> findAllByTenantId(Long tenantId, Specification<T> specification, Sort sort);
    Page<T> findAllByTenantId(Long tenantId, Specification<T> specification, Pageable page);
    List<T> findAllByTenantIdAndIdIn(Long tenantId, Iterable<ID> ids);
    long countByTenantId(Long tenantId);
    long countByTenantId(Long tenantId, Specification<T> specification);
    <S extends T> S save(S entity);
    <S extends T> S saveAndFlush(S entity);
    <S extends T> List<S> saveAll(Iterable<S> entities);
    void delete(T entity);
    void deleteByTenantIdAndId(Long tenantId, ID id);
    void deleteAllByTenantId(Long tenantId);
    void deleteAllByTenantId(Long tenantId, Iterable<? extends T> entities);
    void deleteAllByTenantIdAndIdIn(Long tenantId, Iterable<ID> ids);
    void flush();
}
