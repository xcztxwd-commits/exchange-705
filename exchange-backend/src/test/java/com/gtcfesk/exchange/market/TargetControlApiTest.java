package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.admin.AdminAiControlController;
import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.config.GlobalExceptionHandler;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real V4 HTTP/controller/service flow with a disposable H2 store and an injected source quote. No live services. */
class TargetControlApiTest extends TenantMarketTestContext {
    final ForexQuoteMarketService market = new ForexQuoteMarketService();
    final TradingSymbolRepository symbols = mock(TradingSymbolRepository.class);
    final SystemConfigService configs = mock(SystemConfigService.class);
    final Map<String,String> saved = new HashMap<>();
    ControlHistoryStore store;
    PersistentPriceControl controls;
    MockMvc mvc;
    MarketControlCommands commands;
    static final String INPUT = "\"durationSeconds\":300,\"targetPrice\":100300,\"intensity\":10,\"randomOscillation\":false";
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        DriverManagerDataSource data = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        store = new ControlHistoryStore(new JdbcTemplate(data), new DataSourceTransactionManager(data));
        MarketSqlFixture.schema(store.db); store.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1)");
        store.db.execute("CREATE TABLE tenant(id BIGINT PRIMARY KEY,status VARCHAR(16) NOT NULL,config_ready BOOLEAN NOT NULL)");
        store.db.update("INSERT INTO tenant(id,status,config_ready) VALUES(1,'ACTIVE',TRUE)");
        controls = new PersistentPriceControl(store);
        TradingSymbol symbol = new TradingSymbol(); symbol.setTenantId(1L); symbol.setId(1L); symbol.setSymbol("TEST");
        symbol.setCategory("Metal"); symbol.setSourceCategory("Metal"); symbol.setMarketSource(MarketInstrumentCatalog.inferredSource("Metal"));
        symbol.setPricePrecision(2); symbol.setIsEnabled(true);
        when(symbols.findByTenantIdAndId(1L, 1L)).thenReturn(Optional.of(symbol));
        when(symbols.findAllByTenantId(1L)).thenReturn(Collections.singletonList(symbol));
        when(symbols.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(configs.getConfigValue(anyString())).thenAnswer(call -> saved.get(call.getArgument(0)));
        doAnswer(call -> { saved.put(call.getArgument(0), call.getArgument(1)); return null; }).when(configs).saveConfig(anyString(), anyString(), anyString());
        ReflectionTestUtils.setField(market, "symbols", symbols); ReflectionTestUtils.setField(market, "redis", mock(RedisMarketService.class));
        ReflectionTestUtils.setField(market, "controls", controls); ReflectionTestUtils.setField(market, "controlHistory", store);
        ReflectionTestUtils.setField(market, "klineMerger", new ControlledKlineMerger(store)); ReflectionTestUtils.setField(market, "systemConfigs", configs);
        market.refreshSymbols();
        Map<String,Object> groups = (Map<String,Object>) ReflectionTestUtils.getField(marketState(market), "groups");
        Map<String,Map<String,Object>> quotes = (Map<String,Map<String,Object>>) ReflectionTestUtils.getField(groups.get("Metal"), "quotes");
        Map<String,Object> quote = new HashMap<>(); quote.put("price", new BigDecimal("100000.00"));
        quote.put("timestamp", System.currentTimeMillis()); quote.put("fetchedAt", System.currentTimeMillis()); quote.put("sourceAvailable", true);
        quotes.put("TEST", quote);
        market.completeControls();
        commands = new MarketControlCommands(store, market, mock(com.gtcfesk.exchange.tenant.TenantJobRunner.class), mock(com.gtcfesk.exchange.control.ControlAuditService.class));
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken actor = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("api-fixture", null, Collections.emptyList());
        actor.setDetails(new com.gtcfesk.exchange.control.ControlIdentity(7L, 1L, "api-fixture-session"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(actor);
        AdminAiControlController controller = new AdminAiControlController();
        ReflectionTestUtils.setField(controller, "market", market); ReflectionTestUtils.setField(controller, "controls", controls);
        ReflectionTestUtils.setField(controller, "commands", commands);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close() { commands.stop(); market.stop(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void automaticManualFormulaSaveAndRealStartUseSameSnapshot() throws Exception {
        mvc.perform(post("/api/admin/ai-control/1/preview").contentType(MediaType.APPLICATION_JSON).content("{"+INPUT+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feasible").value(true)).andExpect(jsonPath("$.algorithmVersion").value(4))
                .andExpect(jsonPath("$.deviationBandPercent").value("0.04")).andExpect(jsonPath("$.corridorAmount").value("40.00"));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_control_task", Integer.class));
        String custom = "{"+INPUT+",\"stepFormula\":\"5\",\"deviationBandMode\":\"MANUAL\",\"deviationBandPercent\":\"0.02\"}";
        mvc.perform(put("/api/admin/ai-control/1/formula").contentType(MediaType.APPLICATION_JSON).content(custom))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stepFormula").value("5"));
        assertEquals("5", saved.get("market.control.step-formula.1"));
        mvc.perform(get("/api/admin/ai-control/1/formula")).andExpect(status().isOk()).andExpect(jsonPath("$.stepFormula").value("5"));
        mvc.perform(post("/api/admin/ai-control/1/start").contentType(MediaType.APPLICATION_JSON)
                .content(custom.substring(0, custom.length()-1)+",\"requestKey\":\"api-v4-request-key-001\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("ACCEPTED"));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_control_task", Integer.class));
        commands.runOne();
        mvc.perform(get("/api/admin/ai-control/1/commands").param("requestKey", "api-v4-request-key-001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RUNNING"));
        market.completeControls();
        mvc.perform(get("/api/admin/ai-control/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.algorithmVersion").value(4))
                .andExpect(jsonPath("$.stepFormula").value("5")).andExpect(jsonPath("$.corridorAmount").value("20.00"));
        PersistentPriceControl.Task task = controls.latest(1); String checksum = store.plan(task.id).checksum();
        saved.put("market.control.step-formula.1", "8");
        assertEquals("5", store.plan(task.id).snapshot().get("stepFormula")); assertEquals(checksum, store.plan(task.id).checksum());
        assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM market_control_task", Integer.class));
    }
    @Test void badDraftAndBeanValidationLeaveFormulaAndTasksUntouched() throws Exception {
        saved.put("market.control.step-formula.1", "5");
        mvc.perform(put("/api/admin/ai-control/1/formula").contentType(MediaType.APPLICATION_JSON).content("{"+INPUT+",\"stepFormula\":\"start/0\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_FORMULA"));
        for (String extra : Arrays.asList("\"deviationBandPercent\":-1", "\"deviationBandPercent\":101", "\"deviationBandMode\":\"OTHER\"", "\"stepFormula\":\""+String.join("", Collections.nCopies(257, "1"))+"\""))
            mvc.perform(post("/api/admin/ai-control/1/start").contentType(MediaType.APPLICATION_JSON).content("{"+INPUT+","+extra+"}"))
                    .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/ai-control/1/preview").contentType(MediaType.APPLICATION_JSON).content("{"+INPUT+",\"deviationBandMode\":\"MANUAL\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feasible").value(false));
        assertEquals("5", saved.get("market.control.step-formula.1"));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_control_task", Integer.class));
    }
    @Test void disabledV4NeverSilentlyFallsBackToLegacy() throws Exception {
        ReflectionTestUtils.setField(market, "v4Enabled", false);
        mvc.perform(post("/api/admin/ai-control/1/start").contentType(MediaType.APPLICATION_JSON).content("{"+INPUT+",\"requestKey\":\"api-v4-disabled-001\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("ACCEPTED"));
        commands.runOne();
        mvc.perform(get("/api/admin/ai-control/1/commands").param("requestKey", "api-v4-disabled-001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("ALGORITHM_DISABLED"));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_control_task", Integer.class));
    }
}
