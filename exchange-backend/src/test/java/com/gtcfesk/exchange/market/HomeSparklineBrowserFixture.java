package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.security.servlet.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.servlet.*;
import javax.servlet.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

/** Loopback-only test transport. Identity and live-price seams are synthetic; SQL/Redis/cache/controller are real. */
public class HomeSparklineBrowserFixture {
    static final long A=70005001L,B=70005002L;
    static ConfigurableApplicationContext context;
    static HomeSparklineCache demo;
    static final ObjectMapper JSON=new ObjectMapper();
    static final Set<WebSocketSession> sockets=ConcurrentHashMap.newKeySet();
    static final ScheduledExecutorService frames=Executors.newSingleThreadScheduledExecutor();
    static <T> T in(long tenant,java.util.function.Supplier<T> action){try(TenantContext.Scope ignored=TenantContext.open(tenant)){return action.get();}}
    @org.springframework.boot.test.context.TestConfiguration
    @EnableAutoConfiguration(exclude={SecurityAutoConfiguration.class,UserDetailsServiceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class})
    @Import({HomeSparklineFixture.class,HomeSparklineController.class,Api.class,SocketConfig.class})
    public static class Web {
        @Bean FilterRegistrationBean<OncePerRequestFilter> syntheticIdentity() {
            FilterRegistrationBean<OncePerRequestFilter> bean=new FilterRegistrationBean<>();
            bean.setFilter(new OncePerRequestFilter(){
                protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws java.io.IOException,ServletException {
                    String path=request.getRequestURI();
                    if(!path.startsWith("/api/") && !path.startsWith("/demo-api/")){chain.doFilter(request,response);return;}
                    String token=request.getHeader("Authorization");
                    if(token!=null && !"Bearer t05-a".equals(token) && !"Bearer t05-b".equals(token)){response.sendError(401);return;}
                    long tenant="Bearer t05-b".equals(token)?B:A;
                    try(TenantContext.Scope ignored=TenantContext.open(tenant)){chain.doFilter(request,response);}
                }
            });bean.setOrder(-100);return bean;
        }
    }
    @org.springframework.boot.test.context.TestComponent
    @RestController
    public static class Api {
        @GetMapping({"/api/market/all","/demo-api/market/all"})
        Object symbols(){return Collections.singletonMap("list",context.getBean(TradingSymbolRepository.class).findByTenantIdAndIsEnabledTrueOrderBySortOrderDesc(TenantContext.requireTenantId()));}
        @GetMapping({"/api/market/categories","/demo-api/market/categories"})
        Object categories(){return Collections.singletonMap("list",Arrays.asList(option("Forex"),option("Crypto")));}
        Map<String,Object> option(String key){Map<String,Object> r=new LinkedHashMap<>();r.put("key",key);r.put("label",key);r.put("enabled",true);return r;}
        @PostMapping({"/api/market/redis/price/batch","/api/market/price/batch","/demo-api/market/redis/price/batch","/demo-api/market/price/batch"})
        Object prices(@RequestBody Map<String,Object> request){return MarketPriceController.response(quotes());}
        @PostMapping("/demo-api/market/home-sparkline/batch")
        Object demo(@RequestBody Map<String,Object> request){return MarketPriceController.response(demo.read(MarketPriceController.requestedSymbols(request)));}
        @GetMapping("/__t05/stats")
        Object stats(){return Collections.singletonMap("calculations",context.getBean(HomeSparklineFixture.Source.class).calculations.get());}
        @PostMapping("/__t05/time")
        Object time(@RequestParam long seconds,@RequestParam(defaultValue="false") boolean refresh,@RequestParam(defaultValue="false") boolean empty,@RequestParam(defaultValue="REAL") String mode) {
            if(seconds<0 || seconds>86400)throw new IllegalArgumentException("Fixture time only");
            HomeSparklineCacheTest.Time clock=context.getBean(HomeSparklineCacheTest.Time.class);
            clock.ms.set(java.time.Instant.parse("2026-09-30T00:00:00Z").toEpochMilli()+seconds*1000);
            for(long tenant:new long[]{A,B})in(tenant,()->{
                TradingSymbolRepository repo=context.getBean(TradingSymbolRepository.class);
                double base="DEMO".equals(mode)?9.1:tenant==A?1.1+seconds/3000.0:8.1;
                context.getBean(HomeSparklineFixture.Source.class).stage(repo.findByTenantIdAndIsEnabledTrueOrderBySortOrderDesc(tenant),base,empty);
                return null;
            });
            if(refresh) new HomeSparklineRefreshJob(new TenantJobRunner(context.getBean(JdbcTemplate.class),
                context.getBean(PlatformTransactionManager.class)),"DEMO".equals(mode)?demo:context.getBean(HomeSparklineCache.class)).refresh();
            return stats();
        }
    }
    @org.springframework.boot.test.context.TestConfiguration @EnableWebSocket
    public static class SocketConfig implements WebSocketConfigurer {
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(new TextWebSocketHandler(){
                public void afterConnectionEstablished(WebSocketSession session){sockets.add(session);}
                protected void handleTextMessage(WebSocketSession session,TextMessage message)throws Exception {
                    Map<?,?> m=JSON.readValue(message.getPayload(),Map.class);
                    if("ping".equals(m.get("action")))session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
                    if("subscribe".equals(m.get("action")))session.sendMessage(new TextMessage(JSON.writeValueAsString(new HashMap<String,Object>(){{put("type","subscribed");put("symbols",Arrays.asList("EURUSD","BTCUSD"));}})));
                }
                public void afterConnectionClosed(WebSocketSession session,CloseStatus status){sockets.remove(session);}
            },"/api/ws/market","/demo-api/ws/market").setAllowedOrigins("http://127.0.0.1:18515");
        }
    }
    static Map<String,Object> quotes() {
        long now=System.currentTimeMillis();Map<String,Object> result=new LinkedHashMap<>();
        for(String name:Arrays.asList("EURUSD","BTCUSD")){
            Map<String,Object> q=new LinkedHashMap<>();q.put("price",("EURUSD".equals(name)?1.1:50)+Math.sin(now/150.0)/100);
            q.put("timestamp",now);q.put("fetchedAt",now);q.put("expiresAt",now+60000);q.put("status","available");q.put("available",true);
            q.put("quoteVersion",now);q.put("epoch","t05-live-quotes");q.put("changePct24h",1.2);result.put(name,q);
        }return result;
    }
    public static void main(String[] args)throws Exception {
        if(!Boolean.getBoolean("t05.browser"))throw new IllegalStateException("Explicit isolated browser opt-in required");
        HomeSparklineFixture.requireStack();TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication app=new SpringApplication(Web.class);Map<String,Object> props=new HashMap<>();
        props.put("server.port",18505);props.put("server.address","127.0.0.1");props.put("spring.main.banner-mode","off");
        props.put("logging.level.root","WARN");props.put("spring.jmx.enabled",false);
        app.setDefaultProperties(props);context=app.run("--server.port=18505","--server.address=127.0.0.1");
        JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);jdbc.update("INSERT IGNORE INTO tenant(id) VALUES(?),(?)",A,B);
        TradingSymbolRepository repo=context.getBean(TradingSymbolRepository.class);
        PlatformTransactionManager manager=context.getBean(PlatformTransactionManager.class);
        for(long tenant:new long[]{A,B})in(tenant,()->{
            new TransactionTemplate(manager).execute(tx->{
                for(String name:Arrays.asList("EURUSD","BTCUSD")){
                    TradingSymbol s=new TradingSymbol();s.setSymbol(name);s.setName(name);s.setBaseCurrency("EURUSD".equals(name)?"EUR":"BTC");s.setQuoteCurrency("USD");
                    s.setCategory("EURUSD".equals(name)?"Forex":"Crypto");s.setSourceCategory(s.getCategory());s.setMarketSource("EURUSD".equals(name)?"yahoo":"binance");
                    s.setAlltickSymbol("EURUSD".equals(name)?"EURUSD=X":"BTCUSDT");s.setIsHot(true);s.setPricePrecision(5);s.setSparklineData("[900,901,902]");
                    repo.saveAndFlush(s);
                }return null;
            });
            context.getBean(HomeSparklineFixture.Source.class).stage(repo.findByTenantIdAndIsEnabledTrueOrderBySortOrderDesc(tenant),tenant==A?1.1:8.1,false);
            context.getBean(HomeSparklineCache.class).refresh();return null;
        });
        demo=new HomeSparklineCache(context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class),repo,context.getBean(HomeSparklineFixture.Source.class).quotes,true,context.getBean(HomeSparklineCacheTest.Time.class));
        frames.scheduleWithFixedDelay(()->{
            try{String value=JSON.writeValueAsString(new HashMap<String,Object>(){{put("type","price");put("data",quotes());}});
                for(WebSocketSession s:sockets)if(s.isOpen())synchronized(s){s.sendMessage(new TextMessage(value));}}
            catch(Exception ignored){}
        },200,200,TimeUnit.MILLISECONDS);
        Path ready=Paths.get(System.getProperty("t05.ready"));Files.write(ready,JSON.writeValueAsBytes(Collections.singletonMap("ready",true)));
        Runtime.getRuntime().addShutdownHook(new Thread(()->{frames.shutdownNow();context.close();}));
        System.out.println("T05_BROWSER_READY "+ready);new CountDownLatch(1).await();
    }
}
