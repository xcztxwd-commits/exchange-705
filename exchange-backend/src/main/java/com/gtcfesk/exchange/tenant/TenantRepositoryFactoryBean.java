package com.gtcfesk.exchange.tenant;

import org.springframework.data.jpa.repository.support.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.aopalliance.intercept.MethodInterceptor;
import javax.persistence.EntityManager;

/** Select the restricted base only for private entities. Shared/control repositories remain explicit. */
public class TenantRepositoryFactoryBean<R extends Repository<T, ID>, T, ID>
        extends JpaRepositoryFactoryBean<R, T, ID> {
    public TenantRepositoryFactoryBean(Class<? extends R> repositoryInterface) { super(repositoryInterface); }
    @Override protected RepositoryFactorySupport createRepositoryFactory(EntityManager em) {
        JpaRepositoryFactory factory = new JpaRepositoryFactory(em) {
            @Override protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
                return TenantOwnedEntity.class.isAssignableFrom(metadata.getDomainType())
                        ? TenantRepositoryImpl.class : SimpleJpaRepository.class;
            }
        };
        factory.addRepositoryProxyPostProcessor((proxy, metadata) -> {
            if (!TenantOwnedEntity.class.isAssignableFrom(metadata.getDomainType())) return;
            proxy.addAdvice((MethodInterceptor) invocation -> {
                if (invocation.getMethod().getDeclaringClass() == Object.class) return invocation.proceed();
                TenantContext.requireTenantId();
                if (invocation.getMethod().getName().contains("ByTenantId")) {
                    Object[] arguments = invocation.getArguments();
                    if (arguments.length == 0 || !(arguments[0] instanceof Long)) throw new IllegalArgumentException("缺少租户参数");
                    TenantContext.require((Long) arguments[0]);
                }
                return invocation.proceed();
            });
        });
        return factory;
    }
}
