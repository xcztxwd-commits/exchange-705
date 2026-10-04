package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.AdminAiControlController;
import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.config.GlobalExceptionHandler;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.entity.SystemConfig;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.SystemConfigRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import static org.mockito.Mockito.*;

/**
 * Opt-in, loopback-only real Tomcat/controller/MySQL harness. Provider repository and audit are fixtures.
 * This fixed-token test filter is NOT the production security application and proves no permission gate.
 */
@Configuration
@Profile("s2-http-acceptance")
@EnableAutoConfiguration(excludeName={
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
    "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
    "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration"
})
public class S2CommandHttpFixture {
    static State state;
    public static void main(String[] args) throws Exception {
        S2RuntimeMysqlTest.identity();state=new State();
        ConfigurableApplicationContext context=null;boolean ready=false;
        try {
        SpringApplication app=new SpringApplication(S2CommandHttpFixture.class);
        Map<String,Object> properties=new HashMap<>();properties.put("server.address","127.0.0.1");properties.put("server.port",18085);
        properties.put("spring.main.banner-mode","off");properties.put("logging.level.root","WARN");
        if(args.length>0)properties.put("spring.web.resources.static-locations",Paths.get(args[0]).toAbsolutePath().normalize().toUri().toString());
        app.setDefaultProperties(properties);
        context=app.run("--spring.config.name=s2-command-http-fixture","--spring.profiles.active=s2-http-acceptance","--server.address=127.0.0.1","--server.port=18085");
        state.startClock();state.writeIdentity();
        ConfigurableApplicationContext started=context;
        Runtime.getRuntime().addShutdownHook(new Thread(()->state.close(started)));
        System.out.println("S2_COMMAND_HTTP_FIXTURE_READY loopback=18085 actual=Tomcat+Controller+MySQL security=test-only");
        ready=true;
        } finally{if(!ready)state.close(context);}
    }
    // Fixture instances are wired explicitly; do not autowire provider/admin infrastructure into this isolated app.
    @Bean static InstantiationAwareBeanPostProcessor fixtureWiring(){return new InstantiationAwareBeanPostProcessor(){
        @Override public boolean postProcessAfterInstantiation(Object bean,String name){return !name.startsWith("fixture");}
    };}
    @Bean(name="entityManagerFactory") Object fixtureEntityManagerPlaceholder(){return new Object();}
    @Bean ControlHistoryStore fixtureStore(){return state.helper.store;}
    @Bean PersistentPriceControl fixtureControls(){return state.helper.controls;}
    @Bean ForexQuoteMarketService fixtureMarket(){return state.helper.market;}
    @Bean MarketControlCommands fixtureCommands(){return state.commands;}
    @Bean AdminAiControlController fixtureAdminController(){
        AdminAiControlController controller=new AdminAiControlController();ReflectionTestUtils.setField(controller,"market",state.helper.market);
        ReflectionTestUtils.setField(controller,"controls",state.helper.controls);ReflectionTestUtils.setField(controller,"commands",state.commands);return controller;
    }
    @Bean GlobalExceptionHandler fixtureErrors(){return new GlobalExceptionHandler();}
    @Bean FilterRegistrationBean<OncePerRequestFilter> fixtureIdentityFilter(){
        FilterRegistrationBean<OncePerRequestFilter> registration=new FilterRegistrationBean<>();registration.setOrder(Integer.MIN_VALUE);
        registration.setFilter(new OncePerRequestFilter(){
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                response.setHeader("Cache-Control","no-store");
                if(!request.getRequestURI().startsWith("/api/") && !request.getRequestURI().startsWith("/__acceptance/")){chain.doFilter(request,response);return;}
                String authorization=request.getHeader("Authorization");String supplied=authorization!=null && authorization.startsWith("Bearer ")?authorization.substring(7):"";
                if(!MessageDigest.isEqual(state.token.getBytes(java.nio.charset.StandardCharsets.UTF_8),supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))){response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"message\":\"private acceptance identity required\"}");return;}
                try(TenantContext.Scope scope=TenantContext.open(1L)){S2CommandAcceptanceTest.identity(1L);chain.doFilter(request,response);}
                finally{SecurityContextHolder.clearContext();}
            }
        });return registration;
    }

    static final class State {
        final S2CommandAcceptanceTest helper=new S2CommandAcceptanceTest();
        final String token=UUID.randomUUID()+"-"+UUID.randomUUID();
        final TradingSymbol[] symbols;
        final MarketControlCommands commands;
        final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor();
        final ConcurrentMap<Long,String> modes=new ConcurrentHashMap<>();
        final ConcurrentMap<Long,CountDownLatch> held=new ConcurrentHashMap<>();
        final ConcurrentMap<Long,String> heldStates=new ConcurrentHashMap<>();
        final AtomicReference<String> workerError=new AtomicReference<>();
        final LettuceConnectionFactory redisConnection=new LettuceConnectionFactory(S2RuntimeMysqlTest.ownedRedisConfiguration());
        final StringRedisTemplate redisTemplate;
        final RedisMarketService redis=new RedisMarketService();
        State(){
            helper.setup();
            try {
                redisConnection.afterPropertiesSet();redisTemplate=new StringRedisTemplate(redisConnection);
                try(org.springframework.data.redis.connection.RedisConnection ping=redisConnection.getConnection()){if(!"PONG".equals(ping.ping()))throw new IllegalStateException("owned Redis fixture unavailable");}
                ReflectionTestUtils.setField(redis,"redisTemplate",redisTemplate);redis.startPriceWriter();
                JdbcTemplate observed=new JdbcTemplate(S2RuntimeMysqlTest.data){
                    @Override public int update(String sql,Object... arguments){int result=super.update(sql,arguments);
                        if(sql.contains("SET state='READY'")){
                            Long symbol=queryForObject("SELECT symbol_id FROM market_control_command WHERE tenant_id=1 AND id=?",Long.class,arguments[2]);
                            if("HOLD_READY".equals(modes.get(symbol)))TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){hold(symbol,"READY");}});
                        }
                        return result;
                    }
                };ReflectionTestUtils.setField(helper.store,"db",observed);
                TradingSymbol other=helper.newSymbol(1L);symbols=new TradingSymbol[]{helper.symbol,other};
                helper.market=helper.newMarket(helper.store,helper.controls,symbols);doNothing().when(helper.market).start();
                ReflectionTestUtils.setField(helper.market,"redis",redis);
                ReflectionTestUtils.setField(helper.market,"klineMerger",new ControlledKlineMerger(helper.store));
                helper.store.db.queryForList("SELECT tenant_id,symbol_id,formula FROM s2_acceptance_formula WHERE 1=0");
                ReflectionTestUtils.setField(helper.market,"systemConfigs",formulaStore());
                ControlAuditService audit=mock(ControlAuditService.class);TenantJobRunner jobs=mock(TenantJobRunner.class);
                doAnswer(call->{((Consumer<Long>)call.getArgument(1)).accept(1L);return null;}).when(jobs).eachContext(anyString(),any());
                doAnswer(call->{try(TenantContext.Scope scope=TenantContext.open(1L)){((Runnable)call.getArgument(2)).run();}return null;}).when(jobs).oneContext(anyString(),eq(1L),any());
                commands=new MarketControlCommands(helper.store,helper.market,jobs,audit);
                doAnswer(call->{
                    long symbol=((Number)call.getArgument(0)).longValue();PersistentPriceControl.Prepared prepared=(PersistentPriceControl.Prepared)call.callRealMethod();
                    String mode=modes.get(symbol);
                    if("FAIL_NEXT".equals(mode) && modes.remove(symbol,mode)){prepared.close();throw new BalancedControlPlan.Failure("ACCEPTANCE_PREPARE_FAILURE","isolated injected preparation failure");}
                    if("HOLD".equals(mode)){
                        try{hold(symbol,"PREPARING");}catch(RuntimeException failure){prepared.close();throw failure;}
                    }
                    return prepared;
                }).when(helper.market).prepareCommand(anyLong(),anyInt(),any(BigDecimal.class),anyInt(),anyBoolean(),any(TargetControlOptions.class),anyLong());
                doAnswer(call->{String command=(String)call.getArgument(4);
                    Long symbol=helper.store.db.queryForObject("SELECT symbol_id FROM market_control_command WHERE tenant_id=1 AND id=?",Long.class,command);
                    if("ACK_LOSS_NEXT".equals(modes.get(symbol)) && modes.remove(symbol,"ACK_LOSS_NEXT"))TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){throw new IllegalStateException("isolated committed acknowledgement loss");}});
                    return null;
                }).when(audit).record(any(),eq(1L),any(),eq("ai-control-command.activate"),anyString(),eq("RUNNING"),anyString(),any());
                tick();
            } finally{SecurityContextHolder.clearContext();helper.scope.close();}
        }
        // Actual formula service; only its repository/policy boundary is a private, JDBC-backed fixture adapter.
        SystemConfigService formulaStore(){
            SystemConfigRepository repository=mock(SystemConfigRepository.class);TenantPolicyService policy=mock(TenantPolicyService.class);
            when(policy.effectiveConfig(anyString(),nullable(String.class))).thenAnswer(call->{formulaSymbol(call.getArgument(0));return call.getArgument(1);});
            doAnswer(call->{formulaSymbol(call.getArgument(0));return null;}).when(policy).requireConfigChange(anyString(),anyString());
            when(repository.findByTenantIdAndConfigKey(anyLong(),anyString())).thenAnswer(call->{
                TenantContext.require(call.getArgument(0));long symbol=formulaSymbol(call.getArgument(1));
                List<String> formulas=helper.store.db.queryForList("SELECT formula FROM s2_acceptance_formula WHERE tenant_id=? AND symbol_id=?",String.class,1L,symbol);
                if(formulas.isEmpty())return Optional.empty();SystemConfig config=new SystemConfig();config.setTenantId(1L);config.setConfigKey(call.getArgument(1));config.setConfigValue(formulas.get(0));return Optional.of(config);
            });
            when(repository.save(any(SystemConfig.class))).thenAnswer(call->{SystemConfig config=call.getArgument(0);long symbol=formulaSymbol(config.getConfigKey());
                if(config.getTenantId()!=null)TenantContext.require(config.getTenantId());config.setTenantId(1L);
                helper.store.transaction(()->{helper.store.db.update("INSERT INTO s2_acceptance_formula(tenant_id,symbol_id,formula) VALUES(?,?,?) ON DUPLICATE KEY UPDATE formula=VALUES(formula)",1L,symbol,config.getConfigValue());return null;});return config;
            });
            SystemConfigService service=new SystemConfigService();ReflectionTestUtils.setField(service,"systemConfigRepository",repository);ReflectionTestUtils.setField(service,"tenantPolicy",policy);return service;
        }
        long formulaSymbol(String key){TenantContext.require(1L);for(TradingSymbol symbol:symbols)if(("market.control.step-formula."+symbol.getId()).equals(key))return symbol.getId();throw new org.springframework.security.access.AccessDeniedException("owned fixture formula required");}
        void startClock(){clock.scheduleWithFixedDelay(()->{try(TenantContext.Scope tenant=TenantContext.open(1L)){tick();}catch(RuntimeException failure){workerError.set(failure.getClass().getSimpleName());}},0,1,TimeUnit.SECONDS);}
        void tick(){for(int i=0;i<symbols.length;i++){long now=System.currentTimeMillis();Map<String,Object> quote=S2CommandAcceptanceTest.raw(now);quote.put("price",new BigDecimal(i==0?"90.00":"120.00"));helper.controls.sourceQuote(symbols[i],quote,now);helper.controls.pump(symbols[i],quote,now,60000);redis.savePrice(symbols[i].getSymbol(),helper.controls.display(symbols[i],Collections.emptyMap(),now));}}
        void hold(long symbol,String phase){CountDownLatch gate=new CountDownLatch(1);held.put(symbol,gate);heldStates.put(symbol,phase);
            try{if(!gate.await(120,TimeUnit.SECONDS))throw new IllegalStateException("isolated "+phase+" gate timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            finally{held.remove(symbol,gate);heldStates.remove(symbol);}
        }
        void release(){for(CountDownLatch gate:held.values())gate.countDown();modes.clear();}
        void close(ConfigurableApplicationContext context){
            release();clock.shutdownNow();commands.stop();
            try{if(context!=null)context.close();}
            finally{try{helper.market.stop();}finally{try{redis.stopPriceWriter();}finally{redisConnection.destroy();}}}
        }
        void writeIdentity() throws Exception {
            String location=System.getenv("S2_HTTP_FIXTURE_FILE");if(location==null)throw new IllegalStateException("S2_HTTP_FIXTURE_FILE must name private test-only browser identity");
            Path path=Paths.get(location).toAbsolutePath().normalize();Map<String,Object> identity=new LinkedHashMap<>();
            identity.put("kind","S2_LOOPBACK_TOMCAT_MYSQL_TEST_ONLY");identity.put("owner",S2RuntimeMysqlTest.fixture.get("owner"));identity.put("run",S2RuntimeMysqlTest.fixture.get("run"));
            identity.put("baseUrl","http://127.0.0.1:18085");identity.put("token",token);identity.put("tenantId",1);identity.put("actorId",7);
            identity.put("symbolIds",Arrays.asList(symbols[0].getId(),symbols[1].getId()));identity.put("serverUuid",S2RuntimeMysqlTest.fixture.get("server_uuid"));
            Map<String,Object> user=new LinkedHashMap<>();user.put("id",7);user.put("tenantId",1);user.put("userType","admin");user.put("sessionId","s2-command-session");identity.put("user",user);
            identity.put("securityScope","Fixed private token test filter; not production security acceptance");
            Path temporary=path.resolveSibling(path.getFileName()+".tmp");Files.write(temporary,new ObjectMapper().writeValueAsBytes(identity));Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @RestController @Profile("s2-http-acceptance") static final class GateController {
        @GetMapping("/__acceptance/state") public Map<String,Object> state(){
            Map<String,Object> status=new LinkedHashMap<>();status.put("kind","S2_LOOPBACK_TOMCAT_MYSQL_TEST_ONLY");status.put("heldSymbols",new ArrayList<>(S2CommandHttpFixture.state.held.keySet()));
            status.put("heldStates",new HashMap<>(S2CommandHttpFixture.state.heldStates));status.put("modes",new HashMap<>(S2CommandHttpFixture.state.modes));status.put("workerError",S2CommandHttpFixture.state.workerError.get());status.put("metrics",S2CommandHttpFixture.state.commands.metrics());return status;
        }
        @PostMapping("/__acceptance/{mode}") public Map<String,Object> mode(@PathVariable String mode,@RequestParam(required=false) Long symbolId){
            State fixture=S2CommandHttpFixture.state;if("release".equals(mode)){fixture.release();return state();}
            if("release-prepare".equals(mode)){if(symbolId==null || !"PREPARING".equals(fixture.heldStates.get(symbolId)))throw new IllegalArgumentException("held PREPARING fixture symbol required");fixture.held.get(symbolId).countDown();return state();}
            if("clear-redis".equals(mode)){for(TradingSymbol symbol:fixture.symbols)fixture.redisTemplate.delete("tenant:1:market:price:"+symbol.getSymbol());return state();}
            if(symbolId==null || Arrays.stream(fixture.symbols).noneMatch(value->Objects.equals(value.getId(),symbolId)))throw new IllegalArgumentException("fixture symbol required");
            String value="hold".equals(mode)?"HOLD":"hold-ready".equals(mode)?"HOLD_READY":"fail-next".equals(mode)?"FAIL_NEXT":"ack-loss-next".equals(mode)?"ACK_LOSS_NEXT":null;
            if(value==null)throw new IllegalArgumentException("unknown acceptance gate");fixture.modes.put(symbolId,value);return state();
        }
        // Auxiliary UI directory only. AI-control API always uses the actual controller and MySQL queue.
        @GetMapping("/api/admin/menus/current") public Map<String,Object> menus(){
            Map<String,Object> menu=new LinkedHashMap<>();menu.put("id",1);menu.put("menuCode","ai_control");menu.put("menuName","S2 private acceptance");menu.put("path","/ai-control");
            Map<String,Object> result=new LinkedHashMap<>();result.put("success",true);result.put("menus",Collections.singletonList(menu));result.put("groups",Collections.emptyList());result.put("actions",Collections.singletonMap("ai_control",Arrays.asList("start","preview","stop","restore","manual","random","replace_history")));return result;
        }
        @GetMapping("/api/admin/table-preferences/{table}") public Map<String,Object> table(@PathVariable String table){return Collections.singletonMap("columns",Collections.emptyList());}
        @GetMapping("/api/admin/notification/sounds") public List<Object> sounds(){return Collections.emptyList();}
        @GetMapping("/api/admin/notification/pending-counts") public Map<String,Object> pending(){Map<String,Object> result=new LinkedHashMap<>();result.put("success",true);result.put("data",Collections.emptyMap());return result;}
        @GetMapping("/api/admin/users/online-count") public Map<String,Object> online(){Map<String,Object> result=new LinkedHashMap<>();result.put("success",true);result.put("count",0);return result;}
    }
}
