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
    @Autowired private ContractOrderService contracts;
    @Autowired private OptionOrderService options;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-orders"));
    @PostConstruct public void start() {
        worker.scheduleWithFixedDelay(() -> {
            try { contracts.matchPendingLimitOrders(); } catch (Exception ignored) { }
            try { contracts.checkAndAutoCloseSnapshotOrders(); } catch (Exception ignored) { }
            try { contracts.checkAndForceCloseOrders(quotes.freshPrices()); } catch (Exception ignored) { }
            try { options.settleExpiredOrders(quotes.freshPrices()); } catch (Exception ignored) { }
        }, 1, 1, TimeUnit.SECONDS);
    }
    @PreDestroy public void stop() { worker.shutdownNow(); }
}
