package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real local SQL/rollback and actual queue/fence behavior; H2 is not MySQL lock evidence. */
class MarketControlCommandsIsolationTest {
    @Test void lostWriterCannotFenceAnotherTenantsIdenticalCommandId() {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:command_owner_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        JdbcTemplate db=new JdbcTemplate(source);MarketSqlFixture.schema(db);
        ControlHistoryStore store=new ControlHistoryStore(db,new DataSourceTransactionManager(source));
        ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);
        // Observe the second queue reaching its own validation. Plan preparation is outside this isolation regression.
        when(market.commandConfig(2L)).thenAnswer(call->{assertEquals(Long.valueOf(2L),TenantContext.requireTenantId());throw new BusinessException("Fixture validation stopped before plan preparation");});
        MarketControlCommands commands=new MarketControlCommands(store,market,mock(TenantJobRunner.class),mock(ControlAuditService.class));
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1),(2,2)");
        for(long tenant:new long[]{1L,2L}) db.update("INSERT INTO market_control_command(tenant_id,id,symbol_id,request_key,parameter_hash,parameters_json,state,actor_id,config_revision,control_revision,seed,accepted_at,expires_at) VALUES(?,?,?,'same-request',?,'{}','ACCEPTED',1,0,0,1,?,?)",tenant,id,tenant,repeat('a',64),now,now+300000);
        db.update("INSERT INTO market_engine_runtime(tenant_id,symbol_id,writer_generation,owner_id,lease_until) VALUES(1,1,1,'replacement-writer',?)",now+300000);
        try {
            try(TenantContext.Scope ignored=TenantContext.open(1L)) {
                store.runtime.rememberCommittedGeneration(1L,1L);commands.runOne();
                assertEquals("AUTHORITY_LOST",store.runtime.fenceReason(1L));
                assertEquals("ACCEPTED",state(db,1L,id));commands.runOne();
                verifyNoInteractions(market);
            }
            try(TenantContext.Scope ignored=TenantContext.open(2L)) {
                commands.runOne();assertEquals("FAILED",state(db,2L,id));verify(market).commandConfig(2L);
            }
            assertEquals("ACCEPTED",state(db,1L,id));
            assertEquals("replacement-writer",db.queryForObject("SELECT owner_id FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",String.class));
            assertEquals(1L,db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",Long.class));
        } finally {commands.stop();}
    }
    private String state(JdbcTemplate db,long tenant,String id) {
        return db.queryForObject("SELECT state FROM market_control_command WHERE tenant_id=? AND id=?",String.class,tenant,id);
    }
    private String repeat(char value,int length) {StringBuilder text=new StringBuilder();for(int i=0;i<length;i++)text.append(value);return text.toString();}
}
