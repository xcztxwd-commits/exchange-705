package com.gtcfesk.exchange.activity;

import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.entity.OptionOrder;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
import java.util.List;

/** The saved order is both durable event and unique acknowledgement, not an in-memory callback. */
@Service
public class OrderPromotionEvents {
    private final ActivityService activities;
    private final TenantJobRunner tenants;
    private final UserAccountRepository users;
    private final TransactionTemplate transaction;
    @PersistenceContext private EntityManager entityManager;
    @Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationEnvironment simulation;
    @Autowired(required=false) private com.gtcfesk.exchange.control.OperationalIssueService issues;

    public OrderPromotionEvents(ActivityService activities, TenantJobRunner tenants,
                                UserAccountRepository users, PlatformTransactionManager manager) {
        this.activities = activities;
        this.tenants = tenants;
        this.users = users;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString="${activity.order-events.delay-ms:1000}", initialDelayString="${activity.order-events.initial-delay-ms:1000}")
    public void recover() {
        // The simulation gateway requires request-bound authorization. Keep the durable event
        // pending until an approved durable simulation authorization contract exists.
        if (simulation != null && simulation.enabled()) return;
        tenants.eachContext("order-promotion", tenant -> drain());
    }

    public void drain() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Promotion dispatch cannot run inside a money transaction");
        if (simulation != null && simulation.enabled()) return;
        Long tenant = TenantContext.requireTenantId();
        drain(tenant, "ContractOrder", "API_CONTRACT_ORDER");
        drain(tenant, "OptionOrder", "API_OPTION_ORDER");
    }

    private void drain(Long tenant, String kind, String event) {
        long after = 0;
        while (true) {
            List<Long> ids = entityManager.createQuery("select o.id from " + kind
                    + " o where o.tenantId=:tenant and o.promotionPending=true and o.id>:after order by o.id", Long.class)
                    .setParameter("tenant", tenant).setParameter("after", after)
                    .setMaxResults(100).getResultList();
            if (ids.isEmpty()) break;
            for (Long id : ids) {
                after = id;
                try {
                    transaction.executeWithoutResult(status -> {
                    // Claim and settlement take the user lock first. Never invert that order here.
                    Long user = entityManager.createQuery("select o.userId from " + kind
                            + " o where o.tenantId=:tenant and o.id=:id", Long.class)
                            .setParameter("tenant", tenant).setParameter("id", id).getSingleResult();
                    users.lockById(user).orElseThrow(() -> new IllegalStateException("Promotion user disappeared"));
                    Object order = entityManager.createQuery("select o from " + kind
                            + " o where o.tenantId=:tenant and o.id=:id")
                            .setParameter("tenant", tenant).setParameter("id", id)
                            .setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
                    entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
                    boolean pending = order instanceof ContractOrder
                            ? ((ContractOrder) order).isPromotionPending() : ((OptionOrder) order).isPromotionPending();
                    if (!pending) return;
                    Long owner = order instanceof ContractOrder
                            ? ((ContractOrder) order).getUserId() : ((OptionOrder) order).getUserId();
                    if (!user.equals(owner)) throw new IllegalStateException("Promotion owner changed");
                    activities.trigger(user, event, "AUTH_TRADE");
                    if (order instanceof ContractOrder) ((ContractOrder) order).setPromotionPending(false);
                    else ((OptionOrder) order).setPromotionPending(false);
                    entityManager.flush();
                    });
                } catch (RuntimeException failure) {
                    if (issues != null) issues.failed(tenant, "order-promotion", failure);
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                            "Promotion delivery will retry: tenant={}, kind={}, order={}, type={}",
                            tenant, kind, id, failure.getClass().getSimpleName());
                }
            }
        }
    }
}
