package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.trade.FxContractRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Bounded raw provider companions, carried by the existing fenced runtime snapshot. */
final class FundingConversions {
    static final String BOOK="fundingConversions";
    static final long MAX_AGE=60000;
    private FundingConversions() {}

    private static List<Map<String,Object>> routes(String currency,String source) {
        QuoteCurrencyConversion route=QuoteCurrencyConversion.route(currency,source);
        if(route==null) return Collections.emptyList();
        String inverse="Forex".equals(route.category)?route.code.replace("USD=X","=X"):null;
        String unit=route.code.replace("USD=X","");
        List<String> codes=inverse==null?Collections.singletonList(route.code)
            :Arrays.asList("EUR","GBP","AUD","NZD").contains(unit)?Arrays.asList(route.code,inverse):Arrays.asList(inverse,route.code);
        List<Map<String,Object>> result=new ArrayList<>();
        for(String code:codes) {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("currency",currency);item.put("source",source);item.put("category",route.category);
            item.put("code",code);item.put("inverse",code.equals(inverse));item.put("scale",route.scale);
            result.add(item);
        }
        return result;
    }
    private static List<Map<String,Object>> routes(TradingSymbol config) {
        List<Map<String,Object>> result=new ArrayList<>(routes(config.getQuoteCurrency(),config.getMarketSource()));
        if(FxContractRules.isForex(config) && !"USD".equals(config.getBaseCurrency()) && !"USD".equals(config.getQuoteCurrency()))
            result.addAll(routes(config.getBaseCurrency(),"yahoo"));
        return result;
    }
    private static String key(Map<String,Object> route) {
        return route.get("currency")+"|"+route.get("source")+"|"+route.get("category")+"|"+route.get("code");
    }
    static boolean matches(TradingSymbol config,String code,String category) {
        for(Map<String,Object> route:routes(config))
            if(Objects.equals(code,route.get("code")) && Objects.equals(category,route.get("category"))) return true;
        return false;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) {
        return value instanceof Map?(Map<String,Object>)value:Collections.emptyMap();
    }
    static Map<String,Object> retain(Map<String,Object> quote,TradingSymbol config) {
        Map<String,Object> old=map(quote.get(BOOK)),result=new LinkedHashMap<>();
        for(Map<String,Object> route:routes(config)) if(old.containsKey(key(route))) result.put(key(route),old.get(key(route)));
        return result;
    }

    /** Caller holds this actual symbol's current runtime fence. Never receives a controlled/display price. */
    static boolean record(ControlHistoryStore store,TradingSymbol config,String code,String category,Map<String,Object> raw,long receivedAt) {
        long now=store.runtime.clock(),at=QuoteState.time(raw.get("timestamp"));
        boolean available=!Boolean.FALSE.equals(raw.get("sourceAvailable"));
        if(!QuoteState.valid(raw) || receivedAt<=0 || receivedAt>now+5000) return false;
        Map<String,Object> row=store.db.queryForMap("SELECT quote_json,status_json,writer_generation,control_revision,snapshot_version FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=? FOR UPDATE",ControlHistoryStore.tenant(),config.getId());
        Map<String,Object> quote=row.get("quote_json")==null?new LinkedHashMap<>():store.decode((String)row.get("quote_json"));
        Map<String,Object> book=retain(quote,config);
        boolean accepted=false,changed=false;
        for(Map<String,Object> route:routes(config)) {
            if(!Objects.equals(code,route.get("code")) || !Objects.equals(category,route.get("category"))) continue;
            String key=key(route);Map<String,Object> old=map(book.get(key));
            long oldAt=QuoteState.time(old.get("timestamp"));
            boolean same=at==oldAt && raw.get("price") instanceof Number && old.get("price") instanceof Number
                && number(raw.get("price")).compareTo(number(old.get("price")))==0;
            if(!available) {
                // A delayed failure cannot invalidate a newer provider point.
                if(!same) continue;
                accepted=true;
                if(Boolean.FALSE.equals(old.get("sourceAvailable"))) continue;
                Map<String,Object> failed=new LinkedHashMap<>(old);failed.put("sourceAvailable",false);
                book.put(key,failed);changed=true;continue;
            }
            if(!same && Objects.equals(raw.get("eventId"),old.get("eventId"))) continue;
            if(at<oldAt || at==oldAt && !same && (receivedAt<QuoteState.time(old.get("fetchedAt"))
                    || QuoteState.time(old.get("writerGeneration"))!=QuoteState.time(row.get("writer_generation"))
                    || QuoteState.time(raw.get("ingressSequence"))<=QuoteState.time(old.get("ingressSequence")))) continue;
            if(same) {
                accepted=true;
                // Re-fetch/reconnect may restore availability, never renew the source point's deadline.
                if(Boolean.FALSE.equals(old.get("sourceAvailable")) && QuoteState.time(old.get("expiresAt"))>now) {
                    Map<String,Object> recovered=new LinkedHashMap<>(old);recovered.put("sourceAvailable",true);
                    book.put(key,recovered);changed=true;
                }
                continue;
            }
            long expiresAt=Math.min(at,receivedAt)+MAX_AGE;
            if(expiresAt<=now || !(raw.get("source") instanceof String) || String.valueOf(raw.get("source")).isEmpty()
                    || !(raw.get("eventId") instanceof String) || String.valueOf(raw.get("eventId")).isEmpty()) continue;
            Map<String,Object> receipt=new LinkedHashMap<>(route);
            receipt.put("provider",raw.get("source"));receipt.put("transport",raw.get("transport"));
            receipt.put("price",number(raw.get("price")));receipt.put("timestamp",at);receipt.put("fetchedAt",receivedAt);
            receipt.put("expiresAt",expiresAt);receipt.put("eventId",raw.get("eventId"));receipt.put("sourceAvailable",true);
            receipt.put("writerGeneration",row.get("writer_generation"));
            receipt.put("ingressSequence",raw.get("ingressSequence"));
            receipt.put("receiptVersion",QuoteState.time(row.get("snapshot_version"))+1);
            book.put(key,receipt);accepted=true;changed=true;
        }
        if(!changed) return accepted;
        quote.put(BOOK,book);
        Map<String,Object> status=row.get("status_json")==null?new LinkedHashMap<>():store.decode((String)row.get("status_json"));
        // Companion-only writes must not authorize a pre-STOP/pre-takeover instrument quote.
        if(QuoteState.time(quote.get("writerGeneration"))!=QuoteState.time(row.get("writer_generation"))
                || QuoteState.time(quote.get("controlRevision"))!=QuoteState.time(row.get("control_revision"))
                || QuoteState.time(quote.get("quoteVersion"))!=QuoteState.time(row.get("snapshot_version"))) {
            quote.put("available",false);quote.put("tradeAvailable",false);quote.put("executionExpiresAt",0L);
            // Old engine status is no more authoritative than its old price.
            status.clear();status.put("status","engine_pending");
        }
        store.runtime.snapshot(config.getId(),quote,status,now);
        return accepted;
    }

    static Map<String,Object> conversion(Map<String,Object> quote,String currency,String source,long now) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("conversionCurrency",currency);result.put("conversionSource",source);result.put("conversionRequired",true);
        if(QuoteCurrencyConversion.fixed(currency)) {
            result.put("quoteToUsdRate",BigDecimal.ONE);result.put("conversionAvailable",true);
            result.put("conversionExpiresAt",quote.get("executionExpiresAt"));result.put("conversionBasis","FIXED_USD_USDT_PROTOCOL");
            return result;
        }
        Map<String,Object> book=map(quote.get(BOOK));
        for(Map<String,Object> route:routes(currency,source)) {
            Map<String,Object> receipt=map(book.get(key(route)));
            boolean valid=true;
            for(String field:route.keySet()) if(!sameValue(route.get(field),receipt.get(field))) valid=false;
            long at=QuoteState.time(receipt.get("timestamp")),fetched=QuoteState.time(receipt.get("fetchedAt"));
            long expiresAt=QuoteState.time(receipt.get("expiresAt"));
            if(!valid || !QuoteState.valid(receipt) || !Boolean.TRUE.equals(receipt.get("sourceAvailable"))
                    || fetched<=0 || fetched>now+5000 || at>now+5000 || expiresAt!=Math.min(at,fetched)+MAX_AGE || expiresAt<=now
                    || Math.abs(QuoteState.time(quote.get("executionSampledAt"))-at)>MAX_AGE
                    || !(receipt.get("provider") instanceof String) || String.valueOf(receipt.get("provider")).isEmpty()
                    || !(receipt.get("eventId") instanceof String) || QuoteState.time(receipt.get("receiptVersion"))<=0) continue;
            BigDecimal price=number(receipt.get("price"));
            BigDecimal rate=Boolean.TRUE.equals(route.get("inverse"))?BigDecimal.ONE.divide(price,24,RoundingMode.HALF_UP):price;
            result.put("quoteToUsdRate",rate.multiply(number(route.get("scale"))));result.put("conversionAvailable",true);
            result.put("conversionExpiresAt",expiresAt);result.put("conversionTimestamp",at);
            result.put("conversionSymbol",route.get("code"));result.put("conversionBasis","COMMITTED_RAW_PROVIDER");
            result.put("conversionReceipt",Collections.unmodifiableMap(new LinkedHashMap<>(receipt)));
            return result;
        }
        throw new BusinessException("S3_QUOTE_REJECTED: 持久原始换算收据缺失、路由不符或超过60秒");
    }
    static long validate(Map<String,Object> current,Map<String,Object> expected,long now) {
        long deadline=Long.MAX_VALUE;
        if(Boolean.TRUE.equals(expected.get("conversionRequired"))) {
            if(!Objects.equals(current.get("configuredQuoteCurrency"),expected.get("conversionCurrency"))
                    || !Objects.equals(current.get("configuredSource"),expected.get("conversionSource"))) throw rejected();
            Map<String,Object> actual=conversion(current,(String)expected.get("conversionCurrency"),(String)expected.get("conversionSource"),now);
            for(String key:actual.keySet()) if(!sameValue(actual.get(key),expected.get(key))) throw rejected();
            deadline=QuoteState.time(actual.get("conversionExpiresAt"));
        }
        if(Boolean.TRUE.equals(expected.get("marginConversionRequired"))) {
            if(!Objects.equals(current.get("configuredBaseCurrency"),expected.get("marginConversionCurrency"))
                    || !"Forex".equalsIgnoreCase(String.valueOf(current.get("configuredCategory")))) throw rejected();
            Map<String,Object> actual=conversion(current,(String)expected.get("marginConversionCurrency"),"yahoo",now);
            if(!sameValue(actual.get("quoteToUsdRate"),expected.get("marginBaseToUsdRate"))
                    || !sameValue(actual.get("conversionExpiresAt"),expected.get("marginRateExpiresAt"))
                    || !Objects.equals(actual.get("conversionReceipt"),expected.get("marginConversionReceipt"))
                    || !Boolean.TRUE.equals(expected.get("marginRateAvailable"))) throw rejected();
            deadline=Math.min(deadline,QuoteState.time(actual.get("conversionExpiresAt")));
        }
        return deadline;
    }
    private static BusinessException rejected() {return new BusinessException("S3_QUOTE_REJECTED: 换算收据、路由或费率已变化");}
    private static BigDecimal number(Object value) {return new BigDecimal(value.toString());}
    private static boolean sameValue(Object a,Object b) {
        return a instanceof Number && b instanceof Number?number(a).compareTo(number(b))==0:Objects.equals(a,b);
    }
}
