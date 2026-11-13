package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.simulation.SimulationEnvironment;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.support.WebApplicationContextUtils;
import javax.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Service-key-only market reads. No user identities, wallet records or control commands are exported. */
public final class SimulationMarketRelay {
    private SimulationMarketRelay() {}
    public static boolean matches(HttpServletRequest request) {
        return "/api/simulation/market-feed".equals(request.getServletPath().isEmpty()?request.getRequestURI():request.getServletPath());
    }
    public static void handle(HttpServletRequest request,HttpServletResponse response,SimulationEnvironment environment,
            String key,JdbcTemplate jdbc,ObjectMapper mapper) throws IOException {
        response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        String supplied=request.getHeader("X-Simulation-Inspection-Key");
        if(key==null || key.length()<32 || supplied==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(403);return;
        }
        if(!"POST".equals(request.getMethod())) {response.setStatus(405);return;}
        long tenant;Map<String,Object> command;
        try {
            tenant=Long.parseLong(request.getHeader("X-Simulation-Tenant-Id"));
            if(tenant<=0 || jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE id=?",Integer.class,tenant)!=1)throw new IllegalArgumentException();
            command=mapper.readValue(request.getInputStream(),Map.class);
        } catch(Exception invalid) {response.setStatus(400);return;}
        ConfigurableApplicationContext context=(ConfigurableApplicationContext)WebApplicationContextUtils.getRequiredWebApplicationContext(request.getServletContext());
        if(environment.enabled()) {
            if(!"bootstrap".equals(command.get("op"))) {response.setStatus(403);return;}
            // Normal startup uses component scanning. This authenticated route also initializes a newly hot-loaded bean.
            synchronized(SimulationMarketFeed.class) {
                if(!SimulationMarketFeed.enabled()) {
                    SimulationMarketFeed feed=context.getAutowireCapableBeanFactory().createBean(SimulationMarketFeed.class);
                    context.getBeanFactory().registerSingleton("simulationMarketFeed",feed);
                    ((org.springframework.beans.factory.support.DefaultSingletonBeanRegistry)context.getBeanFactory()).registerDisposableBean("simulationMarketFeed",feed::stop);
                }
            }
            mapper.writeValue(response.getWriter(),Collections.singletonMap("running",SimulationMarketFeed.enabled()));return;
        }
        try(TenantContext.Scope ignored=TenantContext.open(tenant)) {
            ForexQuoteMarketService market=context.getBean(ForexQuoteMarketService.class);
            Map<String,Object> result=new LinkedHashMap<>();result.put("environment","REAL");result.put("tenantId",tenant);
            if("snapshot".equals(command.get("op")))result.putAll(snapshot(command,jdbc,mapper,market,
                    context.getBean(TradingSymbolRepository.class),context.getBean(MarketHoursService.class)));
            else if("kline".equals(command.get("op"))) {
                String symbol=(String)command.get("symbol"),interval=(String)command.get("interval");
                int limit=((Number)command.get("limit")).intValue();
                if(symbol==null || symbol.length()>64 || !market.knownSymbol(symbol) || !KlineIntervals.PUBLIC.contains(interval) || limit<1 || limit>1000)throw new IllegalArgumentException();
                Long end=command.get("endTime")==null?null:((Number)command.get("endTime")).longValue();
                if(end!=null && (end<946684800000L || end>System.currentTimeMillis()+86400000L))throw new IllegalArgumentException();
                result.put("result",end==null?market.internalKline(symbol,interval,limit):market.readOrderSnapshot(()->market.historicalKline(symbol,interval,limit,end)));
            } else throw new IllegalArgumentException();
            result.put("generatedAt",System.currentTimeMillis());mapper.writeValue(response.getWriter(),result);
        } catch(IllegalArgumentException invalid) {response.setStatus(400);}
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> snapshot(Map<String,Object> command,JdbcTemplate jdbc,ObjectMapper mapper,
            ForexQuoteMarketService market,TradingSymbolRepository symbols,MarketHoursService hours) {
        Object requested=command.get("symbols"),routes=command.get("routes");
        if(!(requested instanceof List) || ((List<?>)requested).size()>512 || !(routes instanceof List) || ((List<?>)routes).size()>512)throw new IllegalArgumentException();
        Set<String> wanted=new HashSet<>();
        for(Object code:(List<?>)requested) {if(!(code instanceof String) || ((String)code).length()>64)throw new IllegalArgumentException();wanted.add((String)code);}
        long tenant=TenantContext.requireTenantId();
        return market.readOrderSnapshot(()->{
            Map<Long,TradingSymbol> configs=new HashMap<>();
            for(TradingSymbol config:symbols.findAllByTenantId(tenant))if(wanted.contains(config.getSymbol()))configs.put(config.getId(),config);
            List<Map<String,Object>> records=new ArrayList<>();
            for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM market_engine_runtime WHERE tenant_id=?",tenant)) {
                TradingSymbol config=configs.get(QuoteState.time(row.get("symbol_id")));if(config==null)continue;
                Map<String,Object> quote=decode(mapper,row.get("quote_json")),status=decode(mapper,row.get("status_json"));
                quote.put("tenantId",tenant);quote.put("symbolId",config.getId());quote.put("symbol",config.getSymbol());
                boolean changed=QuoteState.time(quote.get("writerGeneration"))!=QuoteState.time(row.get("writer_generation"))
                        || QuoteState.time(quote.get("quoteVersion"))!=QuoteState.time(row.get("snapshot_version"))
                        || QuoteState.time(quote.get("controlRevision"))!=QuoteState.time(row.get("control_revision"))
                        || QuoteState.time(quote.get("configVersion"))!=config.getRowVersion();
                MarketHoursConfig.Status schedule=hours.status(config);
                quote.put("marketClosed",schedule.closed);quote.put("marketReason",schedule.reason);
                quote.put("marketNextChangeAt",schedule.nextChangeAt);quote.put("marketHoursRevision",schedule.revision);
                if(changed || schedule.closed || !Boolean.TRUE.equals(config.getIsEnabled())) {quote.put("available",false);quote.put("tradeAvailable",false);}
                Map<String,Object> record=new LinkedHashMap<>();record.put("id",config.getId());record.put("symbol",config.getSymbol());
                record.put("quote",quote);record.put("status",status);records.add(record);
            }
            // A newly added symbol may not have an engine snapshot yet. Publish an unavailable fact, never a local fallback.
            Set<Long> captured=new HashSet<>();for(Map<String,Object> record:records)captured.add(QuoteState.time(record.get("id")));
            for(TradingSymbol config:configs.values())if(!captured.contains(config.getId())) {
                Map<String,Object> quote=new HashMap<>();quote.put("tenantId",tenant);quote.put("symbolId",config.getId());quote.put("available",false);
                Map<String,Object> record=new LinkedHashMap<>();record.put("id",config.getId());record.put("symbol",config.getSymbol());record.put("quote",quote);record.put("status",Collections.emptyMap());records.add(record);
            }
            Map<String,Map<String,Object>> raw=new LinkedHashMap<>();
            for(Object value:(List<?>)routes) {
                if(!(value instanceof Map))throw new IllegalArgumentException();Map<?,?> route=(Map<?,?>)value;
                Object code=route.get("code"),category=route.get("category");
                if(!(code instanceof String) || ((String)code).length()>64 || !Arrays.asList("Crypto","CryptoPerpetual","Metal","Forex","US","CFD","Oil","Other").contains(category))throw new IllegalArgumentException();
                raw.put(category+"\n"+code,market.getPrice((String)code,(String)category));
            }
            Map<String,Object> result=new LinkedHashMap<>();result.put("records",records);result.put("raw",raw);return result;
        });
    }
    private static Map<String,Object> decode(ObjectMapper mapper,Object body) {
        try{return body==null?new LinkedHashMap<>():mapper.readerFor(Map.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(body.toString());}
        catch(IOException invalid){throw new IllegalStateException("Invalid committed canonical quote",invalid);}
    }
}
