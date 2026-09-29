package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

@Service @RequiredArgsConstructor
public class ManualOrderPrices {
    private final ForexQuoteMarketService market;
    private static final long WINDOW=720*60000L;
    // Shared UTC windows let the picker and final preview reuse the same historical candles.
    private Map<String,Object> window(TradingSymbol symbol,long minute) {
        long end=Math.min((Math.floorDiv(minute,WINDOW)+1)*WINDOW-1,System.currentTimeMillis());
        // Keep current-window cache keys stable within the minute.
        end=Math.floorDiv(end,60000)*60000+59999;
        return market.historicalKline(symbol.getSymbol(),"1m",720,end);
    }
    public Map<String,Object> minutes(TradingSymbol symbol,String date,String timezone) {
        try {
            ZoneId zone=ZoneId.of(timezone);LocalDate day=LocalDate.parse(date);
            long from=day.atStartOfDay(zone).toInstant().toEpochMilli(),to=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long now=System.currentTimeMillis();
            if(from<0 || from>now)throw new BusinessException("请选择已经发生的日期");
            SortedMap<Long,Map<String,Object>> rows=new TreeMap<>();boolean pending=false,unavailable=false,stale=false;
            for(long cursor=Math.floorDiv(from,WINDOW)*WINDOW;cursor<Math.min(to,now);cursor+=WINDOW) {
                Map<String,Object> response=window(symbol,cursor);
                Object raw=response.get("data");if(!(raw instanceof Map)){unavailable=true;continue;}
                Map<?,?> data=(Map<?,?>)raw;
                pending|=Boolean.TRUE.equals(data.get("pending"));
                unavailable|="unavailable".equals(data.get("status"));stale|="stale".equals(data.get("status"));
                rows.putAll(selectMinutes(response,from,to,now,zone));
            }
            Map<String,Object> out=new LinkedHashMap<>();out.put("minutes",new ArrayList<>(rows.values()));
            out.put("pending",pending);out.put("status",pending?"loading":unavailable?"unavailable":stale?"stale":rows.isEmpty()?"empty":"available");
            out.put("source",symbol.getMarketSource());return out;
        }catch(DateTimeException | NullPointerException e){throw new BusinessException("日期或时区无效");}
    }
    static SortedMap<Long,Map<String,Object>> selectMinutes(Map<String,Object> response,long from,long to,long now,ZoneId zone) {
        SortedMap<Long,Map<String,Object>> result=new TreeMap<>();
        Object data=response.get("data"),rows=data instanceof Map?((Map<?,?>)data).get("kline_list"):null;
        if(rows instanceof List)for(Object item:(List<?>)rows){
            if(!(item instanceof Map))continue;Map<?,?> row=(Map<?,?>)item;
            try{
                long time=new BigDecimal(String.valueOf(row.get("timestamp"))).longValueExact();
                if(time<100000000000L)time=Math.multiplyExact(time,1000L);
                if(time<from || time>=to || time>now || time%60000!=0)continue;
                BigDecimal price=minutePrice(row);
                ZonedDateTime local=Instant.ofEpochMilli(time).atZone(zone);Map<String,Object> entry=new LinkedHashMap<>();
                entry.put("timestamp",time);entry.put("local",local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")));
                entry.put("offset",local.getOffset().toString());entry.put("price",price.toPlainString());result.put(time,entry);
            }catch(NumberFormatException | ArithmeticException | BusinessException ignored){ }
        }
        return result;
    }
    public Map<String,Object> quote(TradingSymbol symbol,long open,long close) {
        Map<String,Object> result=new LinkedHashMap<>();
        Map<String,Object> opening=window(symbol,open);
        Map<String,Object> closing=open==close?opening:window(symbol,close);
        result.put("openPrice",exact(opening,open));result.put("closePrice",exact(closing,close));
        result.put("openRate",rate(symbol,open));result.put("closeRate",rate(symbol,close));
        BigDecimal openingPrice=(BigDecimal)result.get("openPrice");
        result.put("marginRate", !FxContractRules.isForex(symbol) ? openingPrice.multiply((BigDecimal)result.get("openRate"))
            : "USD".equals(symbol.getBaseCurrency()) ? BigDecimal.ONE : "USD".equals(symbol.getQuoteCurrency()) ? openingPrice
            : currencyRate(symbol.getBaseCurrency(),symbol.getMarketSource(),open));
        result.put("source",symbol.getMarketSource());result.put("priceBasis","EXACT_MINUTE_OPEN");
        result.put("openMinute",open);result.put("closeMinute",close);
        return result;
    }
    /** Batch windows rather than one network request per candidate pair. Missing rates stay missing. */
    public NavigableMap<Long,ManualOrderGenerator.Candle> generationCandles(TradingSymbol symbol,long from,long to) {
        if(to<=from || to-from>ManualOrderGenerator.RANGE+60000)throw new BusinessException("生成搜索范围最多七天");
        NavigableMap<Long,ManualOrderGenerator.Candle> result=new TreeMap<>();
        QuoteCurrencyConversion conversion=QuoteCurrencyConversion.fixed(symbol.getQuoteCurrency())?null:QuoteCurrencyConversion.route(symbol.getQuoteCurrency(),symbol.getMarketSource());
        if(!QuoteCurrencyConversion.fixed(symbol.getQuoteCurrency()) && conversion==null)throw new BusinessException("缺少历史换算率");
        QuoteCurrencyConversion baseConversion=FxContractRules.isForex(symbol) && !"USD".equals(symbol.getBaseCurrency()) && !"USD".equals(symbol.getQuoteCurrency())
            ? QuoteCurrencyConversion.route(symbol.getBaseCurrency(),symbol.getMarketSource()) : null;
        for(long cursor=Math.floorDiv(from,WINDOW)*WINDOW;cursor<to;cursor+=WINDOW) {
            SortedMap<Long,Map<String,Object>> primary=selectMinutes(window(symbol,cursor),from,to,System.currentTimeMillis(),ZoneOffset.UTC);
            SortedMap<Long,Map<String,Object>> rates=conversion==null?null:selectMinutes(market.getKline(conversion.code,"1m",720,conversion.category,Math.min(cursor+WINDOW-1,to-1)),from,to,System.currentTimeMillis(),ZoneOffset.UTC);
            SortedMap<Long,Map<String,Object>> baseRates=baseConversion==null?null:selectMinutes(market.getKline(baseConversion.code,"1m",720,baseConversion.category,Math.min(cursor+WINDOW-1,to-1)),from,to,System.currentTimeMillis(),ZoneOffset.UTC);
            for(Map.Entry<Long,Map<String,Object>> e:primary.entrySet()) {
                Map<String,Object> rate=rates==null?null:rates.get(e.getKey());
                if(conversion!=null && rate==null)continue;
                BigDecimal value=conversion==null?BigDecimal.ONE:new BigDecimal(rate.get("price").toString()).multiply(conversion.scale);
                BigDecimal price=new BigDecimal(e.getValue().get("price").toString()),marginRate=price.multiply(value);
                if(FxContractRules.isForex(symbol)) {
                    if("USD".equals(symbol.getBaseCurrency()))marginRate=BigDecimal.ONE;
                    else if("USD".equals(symbol.getQuoteCurrency()))marginRate=price;
                    else {
                        Map<String,Object> baseRate=baseRates==null?null:baseRates.get(e.getKey());
                        if(baseRate==null)continue;
                        marginRate=new BigDecimal(baseRate.get("price").toString()).multiply(baseConversion.scale);
                    }
                }
                if(value.signum()>0)result.put(e.getKey(),new ManualOrderGenerator.Candle(e.getKey(),price,value,marginRate));
            }
        }
        return result;
    }
    private BigDecimal rate(TradingSymbol s,long minute) {
        return currencyRate(s.getQuoteCurrency(),s.getMarketSource(),minute);
    }
    private BigDecimal currencyRate(String currency,String source,long minute) {
        if(QuoteCurrencyConversion.fixed(currency)) return BigDecimal.ONE;
        QuoteCurrencyConversion r=QuoteCurrencyConversion.route(currency,source);
        if(r==null) throw new BusinessException("缺少历史换算率");
        return ManualOrderCalculation.positive(exact(market.getKline(r.code,"1m",2,r.category,minute+59999),minute).multiply(r.scale),"历史换算率");
    }
    public static BigDecimal exact(Map<String,Object> response,long minute) {
        Object data=response==null?null:response.get("data");
        Object rows=data instanceof Map?((Map<?,?>)data).get("kline_list"):null;
        if(rows instanceof List) for(Object value:(List<?>)rows) {
            if(!(value instanceof Map)) continue;
            Map<?,?> row=(Map<?,?>)value;
            try {
                long time=new BigDecimal(String.valueOf(row.get("timestamp"))).longValueExact();
                if(time<100000000000L) time=Math.multiplyExact(time,1000L);
                if(time==minute) {
                    return minutePrice(row);
                }
            } catch(NumberFormatException | ArithmeticException ignored) { }
        }
        Map<?,?> state=data instanceof Map?(Map<?,?>)data:Collections.emptyMap();
        String reason=Boolean.TRUE.equals(state.get("pending"))?"历史行情正在加载，请稍后重新预览":
            "unavailable".equals(state.get("status"))?"行情接口暂不可用，请稍后重试":"该分钟没有有效开盘价，可能休市或数据源未覆盖";
        throw new BusinessException(reason+"（"+state.getOrDefault("code",null)+" / "+Instant.ofEpochMilli(minute)+"）；不使用邻近价格");
    }
    private static BigDecimal minutePrice(Map<?,?> row) {
        BigDecimal price=new BigDecimal(String.valueOf(row.containsKey("open_price")?row.get("open_price"):row.get("open")));
        // Use the same ledger precision for the picker, generator and authoritative preview.
        if(price.scale()>16)price=price.setScale(16,java.math.RoundingMode.HALF_UP);
        return ManualOrderCalculation.positive(price,"分钟起点价格");
    }
}
