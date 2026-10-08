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
    @Test @SuppressWarnings("unchecked") void forexPrecisionFailureReturnsReadOnlyTiersAndExactDiagnosticAmounts() throws Exception {
        symbols.findByTenantIdAndId(1L, 1L).get().setPricePrecision(3);
        Map<String,Object> groups = (Map<String,Object>) ReflectionTestUtils.getField(marketState(market), "groups");
        Map<String,Map<String,Object>> quotes = (Map<String,Map<String,Object>>) ReflectionTestUtils.getField(groups.get("Metal"), "quotes");
        quotes.get("TEST").put("price", new BigDecimal("157.575"));
        String input = "\"durationSeconds\":10,\"targetPrice\":157.588,\"intensity\":1,\"randomOscillation\":false,\"stepFormula\":\""+TargetControlSettings.LEGACY_FORMULA+"\"";
        mvc.perform(post("/api/admin/ai-control/1/preview").contentType(MediaType.APPLICATION_JSON).content("{"+input+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.errorCode").value("AMPLITUDE_PRECISION_UNREPRESENTABLE"))
                .andExpect(jsonPath("$.priceTick").value("0.001"))
                .andExpect(jsonPath("$.theoreticalMinAmount").value("0.001418175"))
                .andExpect(jsonPath("$.theoreticalMaxAmount").value("0.001733325"))
                .andExpect(jsonPath("$.deviationBandPercent").value("0.004"))
                .andExpect(jsonPath("$.corridorAmount").value("0.006"))
                .andExpect(jsonPath("$.tiers.length()").value(10)).andExpect(jsonPath("$.tiers[6].feasible").value(true))
                .andExpect(jsonPath("$.tiers[6].intensity").value(7)).andExpect(jsonPath("$.tiers[6].precision").value(3))
                .andExpect(jsonPath("$.tiers[6].duration").value(10)).andExpect(jsonPath("$.tiers[6].target").value("157588"));
        mvc.perform(post("/api/admin/ai-control/1/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{"+input+",\"deviationBandMode\":\"MANUAL\",\"deviationBandPercent\":0.004}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.tiers[6].deviationBandPercent").value("0.004"))
                .andExpect(jsonPath("$.tiers[6].corridorAmount").value("0.006"));
        assertTrue(saved.isEmpty());
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_control_task", Integer.class));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_control_command", Integer.class));
    }
    @Test @SuppressWarnings("unchecked") void adaptiveForexOneTierCanPreviewStartAndRestoreWithoutChangingInstrumentPrecision() throws Exception {
        symbols.findByTenantIdAndId(1L, 1L).get().setPricePrecision(3);
        Map<String,Object> groups = (Map<String,Object>) ReflectionTestUtils.getField(marketState(market), "groups");
        Map<String,Map<String,Object>> quotes = (Map<String,Map<String,Object>>) ReflectionTestUtils.getField(groups.get("Metal"), "quotes");
        quotes.get("TEST").put("price", new BigDecimal("157.575"));
        String input = "\"durationSeconds\":10,\"targetPrice\":157.588,\"intensity\":1,\"randomOscillation\":false";
        mvc.perform(get("/api/admin/ai-control/1/formula")).andExpect(jsonPath("$.stepFormula").value("base * intensity"));
        mvc.perform(post("/api/admin/ai-control/1/preview").contentType(MediaType.APPLICATION_JSON).content("{"+input+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feasible").value(true)).andExpect(jsonPath("$.mappingVersion").value(4))
                .andExpect(jsonPath("$.baseAmount").value("0.010")).andExpect(jsonPath("$.gapPerSecond").value("0.0013"))
                .andExpect(jsonPath("$.minAmount").value("0.009")).andExpect(jsonPath("$.maxAmount").value("0.011"))
                .andExpect(jsonPath("$.corridorAmount").value("0.040")).andExpect(jsonPath("$.tiers[0].feasible").value(true));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_control_task", Integer.class));
        mvc.perform(post("/api/admin/ai-control/1/start").contentType(MediaType.APPLICATION_JSON)
                .content("{"+input+",\"requestKey\":\"adaptive-jpy-tier-one-001\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("ACCEPTED"));
        commands.runOne();
        mvc.perform(get("/api/admin/ai-control/1/commands").param("requestKey", "adaptive-jpy-tier-one-001"))
                .andExpect(jsonPath("$.state").value("RUNNING"));
        TargetControlPlan restored = store.restorePlan(store.db.queryForMap("SELECT parameters_json,prices_json,checksum FROM market_control_plan"));
        assertEquals(4, restored.snapshot().get("mappingVersion")); assertEquals(1, restored.snapshot().get("intensity"));
        assertEquals(3, restored.precision()); assertEquals(new BigDecimal("157.588"), restored.price(0, 10000));
        assertEquals(3, symbols.findByTenantIdAndId(1L, 1L).get().getPricePrecision()); assertTrue(saved.isEmpty());
    }
    @Test void infeasibleDurationReturnsAdviceAndNeverActivatesEvenWhenCallingStartDirectly() throws Exception {
        String input = "\"durationSeconds\":1,\"targetPrice\":100300,\"intensity\":1,\"randomOscillation\":false";
        mvc.perform(post("/api/admin/ai-control/1/preview").contentType(MediaType.APPLICATION_JSON).content("{"+input+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("增加执行时间")));
        mvc.perform(post("/api/admin/ai-control/1/start").contentType(MediaType.APPLICATION_JSON)
                .content("{"+input+",\"requestKey\":\"adaptive-too-short-001\"}"))
                .andExpect(status().isAccepted());
        commands.runOne();
        mvc.perform(get("/api/admin/ai-control/1/commands").param("requestKey", "adaptive-too-short-001"))
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("增加执行时间")));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_control_task", Integer.class));
    }
    @Test void unexpectedPreparationErrorsRemainOpaque() throws Exception {
        PersistentPriceControl failed = spy(controls);
        doThrow(new IllegalStateException("private internal detail")).when(failed).prepare(any(TradingSymbol.class), anyMap(), any(BigDecimal.class), anyInt(), any(BigDecimal.class), anyInt(), anyBoolean(), any(TargetControlOptions.class));
        ReflectionTestUtils.setField(market, "controls", failed);
        mvc.perform(post("/api/admin/ai-control/1/start").contentType(MediaType.APPLICATION_JSON)
                .content("{"+INPUT+",\"requestKey\":\"adaptive-opaque-error-001\"}"))
                .andExpect(status().isAccepted());
        commands.runOne();
        mvc.perform(get("/api/admin/ai-control/1/commands").param("requestKey", "adaptive-opaque-error-001"))
                .andExpect(jsonPath("$.state").value("FAILED")).andExpect(jsonPath("$.message").value("启动未完成：IllegalStateException"));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_control_task", Integer.class));
    }
    @Test void automaticManualFormulaSaveAndRealStartUseSameSnapshot() throws Exception {
        mvc.perform(post("/api/admin/ai-control/1/preview").contentType(MediaType.APPLICATION_JSON).content("{"+INPUT+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feasible").value(true)).andExpect(jsonPath("$.algorithmVersion").value(4))
                .andExpect(jsonPath("$.amplitudeMode").value("ADAPTIVE")).andExpect(jsonPath("$.baseAmount").value("1.01"))
                .andExpect(jsonPath("$.corridorAmount").value("40.40"));
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
    @Test void restoreSharesDurableQueueAndLostResponseFindsOriginalReceipt() throws Exception {
        String key="api-restore-queued-20261007";
        String input="{\"durationSeconds\":10,\"intensity\":3,\"requestKey\":\""+key+"\"}";
        mvc.perform(post("/api/admin/ai-control/1/restore").contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.action").value("RESTORE"))
                .andExpect(jsonPath("$.state").value("ACCEPTED"));
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task",Integer.class));
        String original=String.valueOf(commands.query(1,key).get("commandId"));
        mvc.perform(post("/api/admin/ai-control/1/restore").contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.commandId").value(original));
        commands.runOne();
        mvc.perform(get("/api/admin/ai-control/1/commands").param("requestKey",key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RUNNING"));
        assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_command",Integer.class));
        commands.runOne();assertEquals(original,commands.query(1,key).get("commandId"));
    }
    @Test void restoreAfterTargetAndManualOffsetStartsAndReturnsToSource() throws Exception {
        String targetKey="api-target-before-manual-20261008",restoreKey="api-restore-after-manual-20261008";
        mvc.perform(post("/api/admin/ai-control/1/start").contentType(MediaType.APPLICATION_JSON)
                .content("{"+INPUT+",\"requestKey\":\""+targetKey+"\"}"))
                .andExpect(status().isAccepted());
        commands.runOne();assertEquals("RUNNING",commands.query(1,targetKey).get("state"));
        mvc.perform(post("/api/admin/ai-control/1/stop")).andExpect(status().isOk());
        mvc.perform(post("/api/admin/ai-control/1/manual").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"offset\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.controlState").value("MANUAL"))
                .andExpect(jsonPath("$.currentPrice").value(100005));
        mvc.perform(post("/api/admin/ai-control/1/restore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationSeconds\":10,\"intensity\":1,\"requestKey\":\""+restoreKey+"\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("ACCEPTED"));
        commands.runOne();
        assertEquals("RUNNING",commands.query(1,restoreKey).get("state"),commands.query(1,restoreKey).toString());
        PersistentPriceControl.Task restore=controls.latest(1);
        assertEquals("RESTORE",restore.kind);assertEquals(0,new BigDecimal("100005").compareTo(restore.startPrice));
        Map<String,Object> flow=store.db.queryForMap("SELECT * FROM market_control_flow WHERE tenant_id=1 AND task_id=?",restore.id);
        assertEquals("RECOVERING",flow.get("state"));
        long start=((Number)flow.get("recovery_started_at")).longValue();
        TradingSymbol symbol=market.commandConfig(1L);
        Map<String,Object> raw=market.getPrice("TEST","Metal");
        for(int second=1;second<=10;second++)controls.pump(symbol,raw,start+second*1000L,60000);
        assertEquals("SOURCE",store.db.queryForObject("SELECT state FROM market_control_flow WHERE tenant_id=1 AND task_id=?",String.class,restore.id));
        assertEquals(false,market.controlStatus(1L).get("enabled"));
        assertEquals(0,new BigDecimal("100000").compareTo(market.freshPrice("TEST")));
    }
    @Test void unknownCancellationPersistsAndBothLateActionsStayCancelled() throws Exception {
        String key="api-unknown-cancel-20261007";
        mvc.perform(post("/api/admin/ai-control/1/stop").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestKey\":\""+key+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.action").value("CANCEL"));
        String original=String.valueOf(commands.query(1,key).get("commandId"));
        mvc.perform(post("/api/admin/ai-control/1/start").contentType(MediaType.APPLICATION_JSON)
                .content("{"+INPUT+",\"requestKey\":\""+key+"\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.commandId").value(original));
        mvc.perform(post("/api/admin/ai-control/1/restore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationSeconds\":10,\"intensity\":3,\"requestKey\":\""+key+"\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("CANCELLED"));
        MarketControlCommands restarted=new MarketControlCommands(store,market,mock(com.gtcfesk.exchange.tenant.TenantJobRunner.class),mock(com.gtcfesk.exchange.control.ControlAuditService.class));
        try{restarted.runOne();assertEquals("CANCELLED",restarted.query(1,key).get("state"));}finally{restarted.stop();}
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task",Integer.class));
    }
    @Test void restoreRequiresOriginalRequestKeyAndRejectsCrossActionReuse() throws Exception {
        mvc.perform(post("/api/admin/ai-control/1/restore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationSeconds\":10,\"intensity\":3}"))
                .andExpect(status().isBadRequest());
        String key="api-start-restore-conflict-20261007";
        commands.accept(1,300,new BigDecimal("100300"),10,false,key,new TargetControlOptions());
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->commands.acceptRestore(1,300,10,false,key));
    }
    @Test void wrappedFenceRetriesAreBoundedPersistedAndDoNotActivate() throws Exception {
        ForexQuoteMarketService failing=spy(market);MarketControlCommands retrying=new MarketControlCommands(store,failing,mock(com.gtcfesk.exchange.tenant.TenantJobRunner.class),mock(com.gtcfesk.exchange.control.ControlAuditService.class));
        String key="api-bounded-fence-retry-20261007";
        doThrow(new org.springframework.jdbc.UncategorizedSQLException("activation","UPDATE market_control_hold",new java.sql.SQLException("ENGINE_FENCED","45000",1644)))
                .when(failing).restoreControl(eq(1L),eq(10),eq(3),eq(false),eq(key));
        try{
            retrying.acceptRestore(1,10,3,false,key);
            for(int attempt=1;attempt<=5;attempt++){
                retrying.runOne();Map<String,Object> receipt=retrying.query(1,key);
                assertEquals(attempt,((Number)receipt.get("retryCount")).intValue());
                assertEquals(attempt==5?"FAILED":"PREPARING",receipt.get("state"));
                if(attempt<5)Thread.sleep(Math.max(0,((Number)receipt.get("retryAt")).longValue()-store.runtime.clock()+10));
            }
            assertEquals("COMMAND_RETRY_EXHAUSTED",retrying.query(1,key).get("errorCode"));
            retrying.runOne();assertEquals(5,((Number)retrying.query(1,key).get("retryCount")).intValue());
            assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task",Integer.class));
        }finally{retrying.stop();}
    }

}
