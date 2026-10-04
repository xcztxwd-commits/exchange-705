package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Committed quote preparation and same-connection current authorization. Never claims an engine lease. */
@Service
public class FundingQuoteAuthority {
    private final ControlHistoryStore store;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;
    public FundingQuoteAuthority(ControlHistoryStore store) { this.store = store; }
    static void stamp(Map<String,Object> quote, TradingSymbol config) {
        quote.put("executionSampledAt",QuoteState.time(quote.get("timestamp")));
        quote.put("configVersion",config.getRowVersion());
        quote.put("pricePrecision",PriceControlPath.precision(config));
        quote.put("configuredSource",config.getMarketSource());
        quote.put("configuredCategory",config.getSourceCategory());
        quote.put("configuredCode",config.getSymbol());
        quote.put("configuredQuoteCurrency",config.getQuoteCurrency());
        quote.put("configuredBaseCurrency",config.getBaseCurrency());
        quote.put("randomSession",Boolean.TRUE.equals(config.getRandomMarketEnabled()) ? config.getRandomMarketStartedAt() : 0L);
    }
    /** Pure preparation from the same durable primary snapshot; no cache, provider fetch or writer lease. */
    public Map<String,Object> conversion(Map<String,Object> quote,String currency,String source) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Conversion preparation requires no outer transaction");
        if(QuoteState.time(quote.get("tenantId"))!=TenantContext.requireTenantId()) throw rejected();
        return FundingConversions.conversion(quote,currency,source,store.runtime.clock());
    }
    public void lockRuntimeIdentity(long symbol) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Runtime identity lock requires transaction");
        store.db.queryForList("SELECT symbol_id FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=? FOR UPDATE",TenantContext.requireTenantId(),symbol);
    }
    public Map<String,Object> prepare(String symbol) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Quote preparation requires no outer transaction");
        List<String> rows=store.db.queryForList("SELECT r.quote_json FROM market_engine_runtime r JOIN trading_symbol s ON s.tenant_id=r.tenant_id AND s.id=r.symbol_id WHERE r.tenant_id=? AND s.symbol=?",String.class,TenantContext.requireTenantId(),symbol);
        if(rows.size()!=1 || rows.get(0)==null) throw rejected();
        Map<String,Object> quote=store.decode(rows.get(0));
        if(!symbol.equals(quote.get("configuredCode"))) throw rejected();
        return Collections.unmodifiableMap(quote);
    }
    /** Funding/order current locks precede this method; all engine writers use runtime first and never funding. */
    public void validate(Collection<Map<String,Object>> quotes) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Quote authority requires the physical funding transaction");
        long tenant=TenantContext.requireTenantId();
        TreeMap<Long,Map<String,Object>> vector=new TreeMap<>();
        long first=Long.MAX_VALUE,last=0,deadline=Long.MAX_VALUE;
        for(Map<String,Object> quote:quotes) {
            if(QuoteState.time(quote.get("tenantId"))!=tenant) throw rejected();
            long symbol=QuoteState.time(quote.get("symbolId"));
            if(symbol<=0) throw rejected();
            Map<String,Object> previous=vector.put(symbol,quote);
            if(previous!=null && !previous.equals(quote)) throw rejected();
            long sampled=QuoteState.time(quote.get("executionSampledAt"));
            if(sampled<=0) throw rejected();
            first=Math.min(first,sampled);last=Math.max(last,sampled);
            deadline=Math.min(deadline,QuoteState.time(quote.get("executionExpiresAt")));
        }
        if(vector.size()>100 || last-first>1000) throw rejected();
        if(vector.isEmpty()) return;
        for(Map.Entry<Long,Map<String,Object>> entry:vector.entrySet()) {
            long symbol=entry.getKey();Map<String,Object> expected=entry.getValue();
            Map<String,Object> runtime=store.db.queryForMap("SELECT writer_generation,control_revision,snapshot_version,quote_json FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=? FOR UPDATE",tenant,symbol);
            Map<String,Object> current=store.decode((String)runtime.get("quote_json"));
            if(!QuoteState.valid(current) || !Boolean.TRUE.equals(current.get("tradeAvailable"))) throw rejected();
            for(String[] key:new String[][]{{"writer_generation","writerGeneration"},{"control_revision","controlRevision"},{"snapshot_version","quoteVersion"}})
                if(QuoteState.time(runtime.get(key[0]))!=QuoteState.time(expected.get(key[1]))) throw rejected();
            for(String key:Arrays.asList("price","timestamp","executionSampledAt","executionExpiresAt","configVersion","pricePrecision","configuredSource","configuredCategory","configuredCode","configuredQuoteCurrency","configuredBaseCurrency","randomSession"))
                if(!Objects.equals(current.get(key),expected.get(key))) throw rejected();
            Map<String,Object> config=store.db.queryForMap("SELECT row_version,is_enabled,price_precision,market_source,source_category,symbol,quote_currency,random_market_enabled,random_market_started_at FROM trading_symbol WHERE tenant_id=? AND id=? FOR UPDATE",tenant,symbol);
            Object enabled=config.get("is_enabled");
            if(!(Boolean.TRUE.equals(enabled) || enabled instanceof Number && ((Number)enabled).intValue()==1)) throw rejected();
            if(QuoteState.time(config.get("row_version"))!=QuoteState.time(current.get("configVersion"))
                    || Math.max(0,Math.min(8,config.get("price_precision")==null ? 2 : ((Number)config.get("price_precision")).intValue()))!=QuoteState.time(current.get("pricePrecision"))) throw rejected();
            for(String[] key:new String[][]{{"market_source","configuredSource"},{"source_category","configuredCategory"},{"symbol","configuredCode"},{"quote_currency","configuredQuoteCurrency"}})
                if(!Objects.equals(config.get(key[0]),current.get(key[1]))) throw rejected();
            Object random=config.get("random_market_enabled");
            long session=Boolean.TRUE.equals(random) || random instanceof Number && ((Number)random).intValue()==1 ? QuoteState.time(config.get("random_market_started_at")) : 0;
            if(session!=QuoteState.time(current.get("randomSession"))) throw rejected();
            if(Boolean.TRUE.equals(expected.get("marginConversionRequired"))) {
                // Only FX cross-margin consumers need this field; old fixed/option fixtures remain unchanged.
                String base=store.db.queryForObject("SELECT base_currency FROM trading_symbol WHERE tenant_id=? AND id=? FOR UPDATE",String.class,tenant,symbol);
                if(!Objects.equals(base,current.get("configuredBaseCurrency"))) throw rejected();
            }
            deadline=Math.min(deadline,FundingConversions.validate(current,expected,store.runtime.clock()));
        }
        final long expiresAt=deadline;
        if(expiresAt<=store.runtime.clock()) throw rejected();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly) {
                TenantContext.require(tenant);
                // JpaTransactionManager does not necessarily register a flush synchronization.
                // The accepted expiry boundary is this locked DB authorization point, not COMMIT acknowledgement.
                if(entityManager!=null) entityManager.flush(); // Standalone JDBC fixtures have no deferred ORM writes.
                if(expiresAt<=store.runtime.clock()) throw rejected();
            }
        });
    }
    private static BusinessException rejected(){return new BusinessException("S3_QUOTE_REJECTED: 报价归属、配置、代次、版本、有效期或完整组合已变化");}
}
