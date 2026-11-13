package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.simulation.SimulationEnvironment;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimulationMarketFeedTest {
    JdbcTemplate db;ControlHistoryStore store;SimulationMarketFeed feed;TradingSymbol config;
    TransactionTemplate transaction;TenantContext.Scope tenant;
    @BeforeEach void setup() {
        tenant=TenantContext.open(1L);
        DriverManagerDataSource data=new DriverManagerDataSource("jdbc:h2:mem:mirror_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(data);DataSourceTransactionManager manager=new DataSourceTransactionManager(data);
        transaction=new TransactionTemplate(manager);store=new ControlHistoryStore(db,manager);
        db.execute("CREATE TABLE trading_symbol(tenant_id BIGINT,id BIGINT,symbol VARCHAR(32),row_version BIGINT,is_enabled BOOLEAN,price_precision INT,market_source VARCHAR(32),source_category VARCHAR(32),quote_currency VARCHAR(16),base_currency VARCHAR(16),random_market_enabled BOOLEAN,random_market_started_at BIGINT,PRIMARY KEY(tenant_id,id))");
        db.execute("CREATE TABLE market_engine_runtime(tenant_id BIGINT,symbol_id BIGINT,writer_generation BIGINT DEFAULT 0,control_revision BIGINT DEFAULT 0,snapshot_version BIGINT DEFAULT 0,quote_json VARCHAR(32000),owner_id VARCHAR(64),lease_until BIGINT DEFAULT 0,committed_at BIGINT DEFAULT 0,status_json VARCHAR(32000),history_restore_revision BIGINT DEFAULT 0,PRIMARY KEY(tenant_id,symbol_id))");
        db.update("INSERT INTO trading_symbol VALUES(1,61,'JPY=X',7,true,3,'yahoo','Forex','JPY','USD',false,NULL)");
        db.execute("CREATE TABLE test_wallet(id INT PRIMARY KEY,amount DECIMAL(20,4))");db.update("INSERT INTO test_wallet VALUES(1,10000)");
        config=new TradingSymbol();config.setTenantId(1L);config.setId(61L);config.setSymbol("JPY=X");
        config.setRowVersion(7L);config.setIsEnabled(true);config.setPricePrecision(3);config.setMarketSource("yahoo");
        config.setSourceCategory("Forex");config.setBaseCurrency("USD");config.setQuoteCurrency("JPY");config.setRandomMarketEnabled(false);
        feed=new SimulationMarketFeed(mock(SimulationEnvironment.class),null,null,store,null);
    }
    @AfterEach void close() {feed.stop();tenant.close();}
    Map<String,Object> canonical() {
        long now=System.currentTimeMillis();Map<String,Object> quote=new LinkedHashMap<>();
        quote.put("tenantId",1L);quote.put("symbolId",61L);quote.put("price",new BigDecimal("159.704"));
        quote.put("timestamp",now);quote.put("fetchedAt",now);quote.put("available",true);quote.put("tradeAvailable",true);
        quote.put("executionExpiresAt",now+60000);quote.put("quoteVersion",500L);quote.put("writerGeneration",4L);
        quote.put("controlRevision",17L);quote.put("configVersion",200L);quote.put("controlState","MANUAL");
        quote.put("controlOffset",new BigDecimal("-0.08"));quote.put("fundingConversions",Collections.singletonMap("kept","canonical-book"));
        return quote;
    }
    @Test void controlledQuoteBecomesLocalFundingAuthorityWithoutChangingWalletOrCanonicalTime() {
        Map<String,Object> canonical=canonical();long now=System.currentTimeMillis();feed.publish(config,canonical,Collections.emptyMap(),now);
        FundingQuoteAuthority authority=new FundingQuoteAuthority(store);Map<String,Object> prepared=authority.prepare("JPY=X");
        assertEquals(0,new BigDecimal(prepared.get("price").toString()).compareTo(new BigDecimal("159.704")));
        assertEquals(canonical.get("timestamp"),prepared.get("timestamp"));assertEquals(500L,((Number)prepared.get("canonicalQuoteVersion")).longValue());
        assertEquals(7L,((Number)prepared.get("configVersion")).longValue());
        assertEquals(canonical.get("fundingConversions"),prepared.get("fundingConversions"));
        transaction.execute(status->{authority.validate(Collections.singletonList(prepared));return null;});
        assertTrue(QuoteState.time(prepared.get("executionExpiresAt"))<=now+3000);
        assertEquals(new BigDecimal("10000.0000"),db.queryForObject("SELECT amount FROM test_wallet WHERE id=1",BigDecimal.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM market_engine_runtime WHERE tenant_id=1",Integer.class));
        assertEquals(500L,canonical.get("quoteVersion"));
    }
    @Test void laterCanonicalPriceInvalidatesAnEarlierFundingReceipt() {
        Map<String,Object> quote=canonical();feed.publish(config,quote,Collections.emptyMap(),System.currentTimeMillis());
        FundingQuoteAuthority authority=new FundingQuoteAuthority(store);Map<String,Object> old=authority.prepare("JPY=X");
        quote.put("price",new BigDecimal("159.700"));quote.put("quoteVersion",501L);
        feed.publish(config,quote,Collections.emptyMap(),System.currentTimeMillis());
        assertThrows(RuntimeException.class,()->transaction.execute(status->{authority.validate(Collections.singletonList(old));return null;}));
        assertEquals(0,new BigDecimal(authority.prepare("JPY=X").get("price").toString()).compareTo(new BigDecimal("159.700")));
    }
    @Test void disconnectCannotRenewAnOldSourceDeadlineOrUseAnIndependentPrice() {
        Map<String,Object> quote=canonical();quote.put("executionExpiresAt",System.currentTimeMillis()-1);
        feed.publish(config,quote,Collections.emptyMap(),System.currentTimeMillis());
        assertFalse((Boolean)store.runtime.read(61,false,System.currentTimeMillis()).get("available"));
        assertFalse((Boolean)feed.rawPrice("JPY=X","Forex").get("available"));
        quote=canonical();quote.put("available",false);
        assertFalse((Boolean)SimulationMarketFeed.localQuote(config,quote,System.currentTimeMillis()).get("tradeAvailable"));
        quote=canonical();quote.put("marketClosed",true);
        assertFalse((Boolean)SimulationMarketFeed.localQuote(config,quote,System.currentTimeMillis()).get("available"));
    }
    @Test void anotherTenantOrInstrumentCannotEnterTheSimulationLedger() {
        Map<String,Object> quote=canonical();quote.put("tenantId",2L);
        assertThrows(IllegalArgumentException.class,()->SimulationMarketFeed.localQuote(config,quote,System.currentTimeMillis()));
        quote.put("tenantId",1L);quote.put("symbolId",62L);
        assertThrows(IllegalArgumentException.class,()->SimulationMarketFeed.localQuote(config,quote,System.currentTimeMillis()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM market_engine_runtime",Integer.class));
    }
    @Test void activeMirrorBlocksIndependentProviderEventsAndUsesCanonicalCandles() {
        ReflectionTestUtils.setField(SimulationMarketFeed.class,"current",feed);
        ForexQuoteMarketService market=new ForexQuoteMarketService();
        assertFalse(market.acceptQuote("JPY=X","Forex",canonical(),"ws",System.currentTimeMillis()));
        market.completeControls(System.currentTimeMillis());
        assertFalse((Boolean)market.getPrice("JPY=X","Forex").get("available"));
        assertFalse(market.internalPrice("JPY=X").containsKey("price"));
    }
    @Test void authenticatedBatchDrivesDisplayedPriceConversionAndUnmodifiedCanonicalCandles() throws Exception {
        com.gtcfesk.exchange.repository.TradingSymbolRepository repository=mock(com.gtcfesk.exchange.repository.TradingSymbolRepository.class);
        when(repository.findAllByTenantId(1L)).thenReturn(Collections.singletonList(config));
        feed.stop();feed=new SimulationMarketFeed(mock(SimulationEnvironment.class),null,repository,store,null);
        ReflectionTestUtils.setField(feed,"identityUrl","http://canonical.test/api/simulation");ReflectionTestUtils.setField(feed,"key","12345678901234567890123456789012");
        MockRestServiceServer server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(feed,"http")).build();
        Map<String,Object> record=new LinkedHashMap<>();record.put("id",61);record.put("symbol","JPY=X");record.put("quote",canonical());record.put("status",Collections.emptyMap());
        Map<String,Object> raw=canonical();raw.put("price",new BigDecimal("159.784"));raw.put("sourceAvailable",true);
        Map<String,Object> response=new LinkedHashMap<>();response.put("environment","REAL");response.put("tenantId",1);
        response.put("records",Collections.singletonList(record));response.put("raw",Collections.singletonMap("Forex\nJPY=X",raw));
        server.expect(requestTo("http://canonical.test/api/simulation/market-feed"))
            .andExpect(header("X-Simulation-Tenant-Id","1")).andExpect(header("X-Simulation-Inspection-Key","12345678901234567890123456789012"))
            .andRespond(withSuccess(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response),MediaType.APPLICATION_JSON));
        feed.refresh();
        assertEquals(0,new BigDecimal(feed.price("JPY=X").get("price").toString()).compareTo(new BigDecimal("159.704")));
        assertEquals(0,new BigDecimal(feed.rawPrice("JPY=X","Forex").get("price").toString()).compareTo(new BigDecimal("159.784")));
        server.verify();server.reset();
        server.expect(requestTo("http://canonical.test/api/simulation/market-feed")).andExpect(jsonPath("$.op").value("kline"))
            .andRespond(withSuccess("{\"tenantId\":1,\"environment\":\"REAL\",\"result\":{\"ret\":200,\"data\":{\"kline_list\":[{\"close_price\":159.704}]}}}",MediaType.APPLICATION_JSON));
        assertEquals(new BigDecimal("159.704"),((Map<?,?>)((List<?>)((Map<?,?>)feed.kline("JPY=X","1m",10,null).get("data")).get("kline_list")).get(0)).get("close_price"));
        server.verify();
    }
}
