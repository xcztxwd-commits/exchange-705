package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.tenant.TenantJobRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="market.home-sparkline.enabled",havingValue="true",matchIfMissing=true)
public class HomeSparklineRefreshJob {
    private final TenantJobRunner tenants;
    private final HomeSparklineCache cache;
    public HomeSparklineRefreshJob(TenantJobRunner tenants,HomeSparklineCache cache) { this.tenants=tenants; this.cache=cache; }
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron="0 */5 * * * *",zone="UTC")
    public void refresh() { tenants.each("home-sparkline",id->cache.refresh()); }
}
