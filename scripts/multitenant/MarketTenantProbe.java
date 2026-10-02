package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import java.io.File;
import java.math.BigDecimal;
import java.util.*;

/** Standalone real-MySQL probe: no scheduler, Spring boot, external price feed or mock. */
public final class MarketTenantProbe {
    static int checks;
    static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
    static void denied(Runnable command, String description) {
        try { command.run(); } catch (RuntimeException expected) { checks++; return; }
        throw new AssertionError(description);
    }
    static TradingSymbol symbol(long id,long tenant,String code) {
        try(TenantContext.Scope ignored=TenantContext.open(tenant)) {
            TradingSymbol result=new TradingSymbol(); result.setId(id);result.setTenantId(tenant);
            result.setSymbol(code);result.setPricePrecision(2);return result;
        }
    }
    static void copySymbol(JdbcTemplate db,long id,long tenant,String code) {
        List<String> fields=db.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trading_symbol' ORDER BY ORDINAL_POSITION",String.class);
        List<String> values=new ArrayList<>();
        for(String field:fields) {
            if(!field.matches("[a-z0-9_]+"))throw new IllegalStateException("Unexpected schema identifier");
            if(field.equals("id"))values.add(String.valueOf(id));
            else if(field.equals("tenant_id"))values.add(String.valueOf(tenant));
            else if(field.equals("symbol")||field.equals("market_instrument_key"))values.add("'"+code+"'");
            else values.add("`"+field+"`");
        }
        db.update("INSERT INTO trading_symbol(`"+String.join("`,`",fields)+"`) SELECT "+String.join(",",values)+" FROM trading_symbol WHERE tenant_id=1 AND id=1");
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String,String> connection=new ObjectMapper().readValue(new File(args[0]),Map.class);
        DriverManagerDataSource data=com.gtcfesk.exchange.tenant.DedicatedMysqlFixture.open(connection);
        JdbcTemplate db=new JdbcTemplate(data);
        ControlHistoryStore store=new ControlHistoryStore(db,new DataSourceTransactionManager(data));store.migrate();
        PersistentPriceControl controls=new PersistentPriceControl(store);
        copySymbol(db,81001,2,"PROBE_A");copySymbol(db,81002,3,"PROBE_B");
        TradingSymbol a=symbol(81001,2,"PROBE_A"),b=symbol(81002,3,"PROBE_B");
        denied(()->store.lastQuote(81001),"Missing context must reject");
        PersistentPriceControl.Task taskA;
        long now=System.currentTimeMillis(),minute=now/60000*60000;
        BigDecimal start=new BigDecimal("100000.00"),target=new BigDecimal("100060.00");
        Map<String,Object> quote=new HashMap<>();quote.put("price",start);quote.put("timestamp",now);
        quote.put("sourceTimestamp",now);quote.put("available",true);quote.put("eventId","same-event");
        Map<String,Object> candle=new LinkedHashMap<>();candle.put("timestamp",minute);
        for(String key:Arrays.asList("open_price","high_price","low_price","close_price"))candle.put(key,start);
        candle.put("volume",BigDecimal.ONE);
        try(TenantContext.Scope ignored=TenantContext.open(2L)) {
            controls.sourceQuote(a,quote,now);
            store.sourceCandles(a.getId(),"1m",Collections.singletonList(candle),now);
            check(store.candles(a.getId(),"1m",minute,minute).size()==1,"A candle write/read");
            PersistentPriceControl.Prepared prepared=controls.prepare(a,quote,start,60,target,10,true);
            taskA=controls.startPrepared(a,quote,start,60,target,10,true,"same-request",null,prepared);
            check(taskA.algorithmVersion==3,"V3 plan saved");
            check(store.plan(taskA.id).checksum().equals(prepared.plan.checksum()),"Plan cache warmed in A");
            controls.advance(a.getId(),taskA.plannedEnd);
            check(controls.history(a.getId(),null).size()==1,"Tenant history");
            controls.display(a,quote,taskA.plannedEnd+1000);
            controls.replaceHistory(a.getId(),taskA.id);
            store.captureLegacyMinute(a.getId(),now);
            store.visibleMixed(a.getId(),minute,taskA.plannedEnd+60000);
            Map<String,Object> external=new HashMap<>(),externalData=new HashMap<>();
            externalData.put("kline_list",Collections.emptyList());external.put("data",externalData);
            new ControlledKlineMerger(store).merge(a.getId(),"1m",10,null,external,null);
            check(!controls.runningSymbols().contains(b.getId()),"Running symbol OR scope");
        }
        try(TenantContext.Scope ignored=TenantContext.open(3L)) {
            check(store.lastQuote(a.getId()).isEmpty(),"Foreign raw quote hidden");
            check(store.candles(a.getId(),"1m",minute,minute).isEmpty(),"Foreign source candles hidden");
            check(controls.history(a.getId(),null).isEmpty(),"Foreign task history hidden");
            denied(()->store.plan(taskA.id),"Warm A plan cannot leak from cache into B");
            denied(()->taskA.price(taskA.plannedEnd),"A task object cannot execute under B");
            denied(()->store.sourceCandles(a.getId(),"1m",Collections.singletonList(candle),now),"Foreign symbol write lock rejected");
            denied(()->controls.sourceQuote(a,quote,now),"Foreign config object rejected");
            controls.sourceQuote(b,quote,now);
            PersistentPriceControl.Prepared prepared=controls.prepare(b,quote,start,60,target,10,true);
            PersistentPriceControl.Task task=controls.startPrepared(b,quote,start,60,target,10,true,"same-request",null,prepared);
            check(!task.id.equals(taskA.id),"Tenant idempotency request does not collide");
            check(!controls.runningSymbols().contains(a.getId()),"Other tenant hold does not bypass OR predicate");
            check(controls.runningSymbols().contains(b.getId()),"B own active task returned");
            controls.stop(b.getId(),System.currentTimeMillis());
        }
        check(TenantContext.currentTenantId()==null,"Context restored after tenant scopes");
        System.out.println("MARKET_TENANT_MYSQL_PASS checks="+checks);
    }
}
