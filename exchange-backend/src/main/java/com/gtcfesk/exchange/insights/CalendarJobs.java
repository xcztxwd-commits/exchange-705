package com.gtcfesk.exchange.insights;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import java.time.Instant;
@Component @RequiredArgsConstructor
public class CalendarJobs {
    private final CalendarSync sync;
    private final CalendarService service;
    private final TenantJobRunner tenants;
    @Value("${calendar.reminders.enabled:true}") private boolean reminders;
    @Scheduled(fixedDelayString="${calendar.job-ms:60000}",initialDelayString="${calendar.initial-delay-ms:60000}")
    public void tick(){
        if(sync.enabled()){for(String source:CalendarSync.URLS.keySet())sync.sync(source);tenants.each("calendar-sync",id->sync.importAllCached());}
        if(reminders)tenants.each("calendar-reminders",id->service.dispatchDue(Instant.now()));
    }
}
