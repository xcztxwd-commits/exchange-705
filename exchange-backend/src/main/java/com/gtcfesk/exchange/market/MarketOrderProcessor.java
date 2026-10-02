package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.trade.ContractOrderService;
import com.gtcfesk.exchange.trade.OptionOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.*;

@Component
public class MarketOrderProcessor {
    @Autowired private ForexQuoteMarketService quotes;
    @Autowired private com.gtcfesk.exchange.tenant.TenantJobRunner tenantJobs;
    @Autowired private ContractOrderService contracts;
    @Autowired private OptionOrderService options;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-orders"));
    @PostConstruct public void start() {
        worker.scheduleWithFixedDelay(() -> {
            // Disabled tenants retain all existing settlement duties; failure of one must not skip another.
            try {
                tenantJobs.each("contract-match",id -> contracts.matchPendingLimitOrders());
                tenantJobs.each("contract-auto-close",id -> contracts.checkAndAutoCloseSnapshotOrders());
                tenantJobs.each("contract-force-close",id -> contracts.checkAndForceCloseOrders(quotes.freshPrices()));
                settleOptions();
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Market settlement tenant enumeration failed", failure);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    // Expiry fetches each executable quote inside its per-order transaction. Preloading an unused
    // map here retains the display/history symbol lock in the suspended outer transaction.
    public void settleOptions() {
        tenantJobs.each("option-settle", id -> options.settleExpiredOrders(java.util.Collections.emptyMap()));
    }
    @PreDestroy public void stop() { worker.shutdownNow(); }
}
