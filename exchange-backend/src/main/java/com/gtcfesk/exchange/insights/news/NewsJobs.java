package com.gtcfesk.exchange.insights.news;
import lombok.RequiredArgsConstructor;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;import com.gtcfesk.exchange.tenant.TenantJobRunner;import java.util.*;
@Component @RequiredArgsConstructor
public class NewsJobs {
    private final NewsSync sync;private final NewsService service;private final TenantJobRunner tenants;
    @Scheduled(fixedDelayString="${news.job-ms:60000}",initialDelayString="${news.initial-delay-ms:60000}")
    public void tick(){if(!sync.enabled())return;Set<String> active=new LinkedHashSet<>();tenants.each("news-enabled-sources",id->active.addAll(service.enabledSourceIds()));for(String source:active)sync.sync(source);tenants.each("news-cache-import",id->{for(String source:service.enabledSourceIds())service.importCached(source);});}
}
