package com.gtcfesk.exchange.market;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A wake-up only: consumers must read their own committed tenant snapshot. */
final class MarketQuoteCommitted {
    final long tenantId;
    final String symbol;
    MarketQuoteCommitted(long tenantId, String symbol) { this.tenantId = tenantId; this.symbol = symbol; }
    static void publish(ApplicationEventPublisher publisher, long tenantId, String symbol) {
        if (publisher == null) return;
        Runnable notify = () -> {
            try { publisher.publishEvent(new MarketQuoteCommitted(tenantId, symbol)); }
            catch (RuntimeException unavailable) {
                org.slf4j.LoggerFactory.getLogger(MarketQuoteCommitted.class).warn("Committed market notification unavailable for tenant {}", tenantId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { notify.run(); }
            });
        } else notify.run();
    }
}
