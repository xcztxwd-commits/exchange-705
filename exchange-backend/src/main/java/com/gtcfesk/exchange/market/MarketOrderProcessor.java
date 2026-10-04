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
    @Autowired private SourceHistoryProjector historyProjector;
    @org.springframework.beans.factory.annotation.Value("${app.market.s4-source-projection-enabled:false}") private boolean s4SourceProjectionEnabled;
    @org.springframework.beans.factory.annotation.Value("${app.market.s3-scheduling-enabled:false}") private boolean s3SchedulingEnabled;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-orders"));
    @org.springframework.beans.factory.annotation.Value("${app.market.processor.auto-start:true}") private boolean autoStart=true;
    @PostConstruct public void start() {
        if(!autoStart) return; // Deterministic owned tests still exercise the actual public runOnce path.
        worker.scheduleWithFixedDelay(this::runOnce, 1, 1, TimeUnit.SECONDS);
    }
    public void runOnce() {
            // Disabled tenants retain all existing settlement duties; failure of one must not skip another.
            try {
                if(s3SchedulingEnabled) {
                    tenantJobs.eachContext("contract-match-s3",id -> contracts.matchPendingLimitOrdersS3(100));
                    tenantJobs.eachContext("contract-auto-close-s3",id -> contracts.checkAndAutoCloseOrdersS3(100));
                    tenantJobs.eachContext("contract-force-close-s3",id -> contracts.checkAndForceCloseOrdersS3(100));
                } else {
                tenantJobs.each("contract-match",id -> contracts.matchPendingLimitOrders());
                tenantJobs.each("contract-auto-close",id -> contracts.checkAndAutoCloseSnapshotOrders());
                tenantJobs.each("contract-force-close",id -> contracts.checkAndForceCloseOrders(quotes.freshPrices()));
                }
                settleOptions();
                if(s4SourceProjectionEnabled) tenantJobs.eachContext("source-history-project",id -> historyProjector.projectNextPage(16));
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Market settlement tenant enumeration failed", failure);
            }
        
    }
    // Expiry scan is context-only; the per-order money transaction is the sole scheduler borrower.
    // The service selects committed S3 authority before each enabled funding transaction; legacy keeps its adapter.
    public void settleOptions() {
        tenantJobs.eachContext("option-settle", id -> options.settleExpiredOrders(java.util.Collections.emptyMap()));
    }
    @PreDestroy public void stop() { worker.shutdownNow(); }
}
