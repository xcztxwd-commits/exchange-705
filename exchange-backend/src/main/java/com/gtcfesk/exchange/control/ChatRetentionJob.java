package com.gtcfesk.exchange.control;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit control-plane enumeration; disabled-by-default tenant policies are never cleaned. */
@Component @RequiredArgsConstructor
public class ChatRetentionJob {
    private final JdbcTemplate jdbc;
    private final ChatRetentionService retention;
    @org.springframework.beans.factory.annotation.Autowired private OperationalIssueService issues;
    @Scheduled(cron="${control.retention.cron:0 17 * * * *}",zone="UTC")
    public void run() {
        if(com.gtcfesk.exchange.tenant.TenantContext.currentTenantId()!=null)throw new IllegalStateException("留存任务不能从租户请求启动");
        for(Long tenant:jdbc.queryForList("SELECT tenant_id FROM tenant_policy WHERE policy_key='retention.auto_delete_enabled' AND policy_value='true' ORDER BY tenant_id",Long.class)){
            try{retention.cleanAutomatically(tenant);}
            catch(RuntimeException failure){issues.failed(tenant,"chat-retention",failure);org.slf4j.LoggerFactory.getLogger(getClass()).error("Retention failed: tenant={}, type={}",tenant,failure.getClass().getSimpleName());}
        }
    }
}
