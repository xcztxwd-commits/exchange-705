package com.gtcfesk.exchange.tenant;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Consumer;

/** Control-plane enumeration only; every tenant, including disabled tenants, keeps settlement responsibility. */
@Component
public class TenantJobRunner {
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.OperationalIssueService issues;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public TenantJobRunner(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    /** Market orchestration has identity but no enclosing transaction or borrowed connection. */
    public <T> T context(Long tenant, java.util.function.Supplier<T> job) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Context-only job cannot join an outer transaction");
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) { return job.get(); }
    }
    public void oneContext(String name, Long tenant, Runnable job) {
        try { context(tenant, () -> { job.run(); return null; }); }
        catch (RuntimeException failure) { if (issues != null) issues.failed(tenant, name, failure); throw failure; }
    }
    public void eachContext(String name, Consumer<Long> job) {
        if (TenantContext.currentTenantId() != null || org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("全局任务不能从租户请求或事务启动");
        for (Long tenant : jdbc.queryForList("SELECT id FROM tenant ORDER BY id", Long.class)) {
            try { oneContext(name, tenant, () -> job.accept(tenant)); }
            catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Tenant task failed: tenant={}, type={}", tenant, failure.getClass().getSimpleName());
            }
        }
    }
    public void each(Consumer<Long> job) { each("unspecified",job); }
    public void each(String name,Consumer<Long> job) {
        if (TenantContext.currentTenantId() != null) throw new IllegalStateException("全局任务不能从租户请求启动");
        for (Long tenant : jdbc.queryForList("SELECT id FROM tenant ORDER BY id", Long.class)) {
            try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
                transaction.execute(status -> { job.accept(tenant); return null; });
            } catch (RuntimeException failure) {
                if(issues!=null)issues.failed(tenant,name,failure);
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Tenant task failed: tenant={}, type={}", tenant, failure.getClass().getSimpleName());
                // Continue other tenants; the failed tenant's transaction rolls back and the next tick retries.
            }
        }
    }
    public void one(Long tenant, Runnable job) { one("unspecified",tenant,job); }
    public void one(String name,Long tenant,Runnable job){try{call(tenant,()->{job.run();return null;});}catch(RuntimeException failure){if(issues!=null)issues.failed(tenant,name,failure);throw failure;}}
    public <T> T call(Long tenant, java.util.function.Supplier<T> job) {
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            return transaction.execute(status -> job.get());
        }
    }
}
