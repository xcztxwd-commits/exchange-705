package com.gtcfesk.exchange.tenant;

import org.springframework.security.access.AccessDeniedException;

/** Verified server identity only. Never populate from a business DTO/header tenant_id. */
public final class TenantContext {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private static final Object TRANSACTION_TENANT = new Object();
    private TenantContext() {}
    public static Long currentTenantId() { return CURRENT.get(); }
    public static Long requireTenantId() {
        Long id = CURRENT.get();
        if (id == null || id <= 0) throw new AccessDeniedException("缺少有效租户上下文");
        bindTransaction(id);
        return id;
    }
    public static void require(Long id) {
        if (!requireTenantId().equals(id)) throw new AccessDeniedException("租户范围不匹配");
    }
    public static Scope open(Long id) {
        if (id == null || id <= 0) throw new AccessDeniedException("租户范围不匹配");
        Long previous = CURRENT.get();
        if (previous != null && !previous.equals(id)) throw new AccessDeniedException("禁止在上下文中切换租户");
        Object bound = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(TRANSACTION_TENANT);
        if (bound != null && !bound.equals(id)) throw new AccessDeniedException("禁止在事务中切换租户");
        CURRENT.set(id);
        return new Scope(previous);
    }
    public static void clear() { CURRENT.remove(); }
    private static void bindTransaction(Long id) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return;
        Object bound=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(TRANSACTION_TENANT);
        if(bound!=null){if(!bound.equals(id))throw new AccessDeniedException("禁止在事务中切换租户");return;}
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(TRANSACTION_TENANT,id);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void suspend(){org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_TENANT);}
            @Override public void resume(){org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(TRANSACTION_TENANT,id);}
            @Override public void afterCompletion(int status){org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_TENANT);}
        });
    }
    public static final class Scope implements AutoCloseable {
        private final Long previous;
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private Scope(Long previous) { this.previous = previous; }
        @Override public void close() {
            if (owner != Thread.currentThread()) throw new IllegalStateException("租户上下文必须在原线程关闭");
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
