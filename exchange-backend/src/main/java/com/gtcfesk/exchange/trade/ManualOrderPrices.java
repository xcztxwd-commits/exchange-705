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
    public static final int HISTORY_LOADING=425;
    private static final long WINDOW=720*60000L;
    // Shared UTC windows let the picker and final preview reuse the same historical candles.
    private static long windowEnd(long minute) {
        long end=Math.min((Math.floorDiv(minute,WINDOW)+1)*WINDOW-1,System.currentTimeMillis());
        // Keep current-window cache keys stable within the minute.
        return Math.floorDiv(end,60000)*60000+59999;
    }
    private Map<String,Object> window(TradingSymbol symbol,long minute) {
        return market.historicalKline(symbol.getSymbol(),"1m",720,windowEnd(minute));
    }
    private Map<String,Object> currencyWindow(String currency,String source,long minute) {
        if(QuoteCurrencyConversion.fixed(currency))return Collections.emptyMap();
        QuoteCurrencyConversion route=QuoteCurrencyConversion.route(currency,source);
        if(route==null)throw new BusinessException("缺少历史换算率");
        return market.getKline(route.code,"1m",720,route.category,windowEnd(minute));
    }
    /** Read-only chart: the same cached minute feed as final preview, never unfinished or fabricated OHLC. */
    public Map<String,Object> chart(TradingSymbol symbol,String timezone) {
        ZoneId zone;try {zone=ZoneId.of(timezone);}catch(DateTimeException | NullPointerException invalid){throw new BusinessException("时区无效");}
        long now=System.currentTimeMillis(),to=Math.floorDiv(now,60000)*60000,from=Math.max(0,to-ManualOrderGenerator.RANGE);
        SortedMap<Long,Map<String,Object>> rows=new TreeMap<>();boolean pending=false,unavailable=false,stale=false;
        for(long cursor=Math.floorDiv(from,WINDOW)*WINDOW;cursor<to;cursor+=WINDOW) {
            Map<String,Object> response=window(symbol,cursor);Object raw=response.get("data");
            if(!(raw instanceof Map)){unavailable=true;continue;}Map<?,?> state=(Map<?,?>)raw;
            pending|=Boolean.TRUE.equals(state.get("pending"));unavailable|="unavailable".equals(state.get("status"));stale|="stale".equals(state.get("status"));
            rows.putAll(selectMinutes(response,from,to,now,zone));
        }
        rows.values().removeIf(row->!row.containsKey("low") || !row.containsKey("high") || !row.containsKey("close"));
        for(Map<String,Object> row:rows.values())for(String name:Arrays.asList("low","high","close"))row.put(name,((BigDecimal)row.get(name)).toPlainString());
        Map<String,Object> out=new LinkedHashMap<>();out.put("candles",new ArrayList<>(rows.values()));out.put("pending",pending);
        out.put("status",pending?"loading":unavailable?"unavailable":stale?"stale":rows.isEmpty()?"empty":"available");
        out.put("source",symbol.getMarketSource());out.put("from",from);out.put("to",to);out.put("interval","1m");return out;
    }
    /** Bounded newest-first page from the same persisted/source history used by final validation. */
    @org.springframework.transaction.annotation.Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> chart(TradingSymbol symbol,String timezone,Long endTime,int limit) {
        ZoneId zone;try {zone=ZoneId.of(timezone);}catch(DateTimeException | NullPointerException invalid){throw new BusinessException("时区无效");}
        long now=System.currentTimeMillis(),finished=Math.floorDiv(now,60000)*60000,from=Math.max(0,finished-ManualOrderGenerator.RANGE);
        if(limit<2 || limit>200)throw new BusinessException("图表每段仅支持2～200根分钟K线");
        if(endTime!=null && (endTime<from || endTime>now))throw new BusinessException("图表仅支持最近30天已结束的分钟行情");
        long to=endTime==null?finished:Math.min(finished,Math.floorDiv(endTime,60000)*60000+60000);
        // One history read, not fourteen 12-hour reads; stored OHLC is immediately visible while missing source data is fetched in the background.
        Map<String,Object> response=market.historicalKline(symbol.getSymbol(),"1m",limit,to-1);
        Object raw=response.get("data");Map<?,?> state=raw instanceof Map?(Map<?,?>)raw:Collections.emptyMap();
        TreeMap<Long,Map<String,Object>> rows=new TreeMap<>(selectMinutes(response,from,to,now,zone));
        rows.values().removeIf(row->!row.containsKey("low") || !row.containsKey("high") || !row.containsKey("close"));
        while(rows.size()>limit)rows.pollFirstEntry();
        for(Map<String,Object> row:rows.values())for(String name:Arrays.asList("low","high","close"))row.put(name,((BigDecimal)row.get(name)).toPlainString());
        // Warm only the displayed FX windows while users choose times; chart display never waits for conversion data.
        if(QuoteCurrencyConversion.route(symbol.getQuoteCurrency(),symbol.getMarketSource())!=null) {
            Set<Long> windows=new LinkedHashSet<>();for(Long time:rows.keySet())windows.add(Math.floorDiv(time,WINDOW)*WINDOW);
            for(long time:windows){currencyWindow(symbol.getQuoteCurrency(),symbol.getMarketSource(),time);if(FxContractRules.isForex(symbol) && !"USD".equals(symbol.getBaseCurrency()) && !"USD".equals(symbol.getQuoteCurrency()))currencyWindow(symbol.getBaseCurrency(),symbol.getMarketSource(),time);}
        }
        boolean pending=Boolean.TRUE.equals(state.get("pending"));
        long oldest=rows.isEmpty()?Math.max(from,to-limit*60000L):rows.firstKey();
        Map<String,Object> out=new LinkedHashMap<>();out.put("candles",new ArrayList<>(rows.values()));out.put("pending",pending);
        out.put("status",pending?"loading":!(raw instanceof Map) || "unavailable".equals(state.get("status"))?"unavailable":"stale".equals(state.get("status"))?"stale":rows.isEmpty()?"empty":"available");
        out.put("source",symbol.getMarketSource());out.put("from",from);out.put("to",to);out.put("interval","1m");
        out.put("nextEndTime",oldest-1);out.put("hasMore",oldest>from);return out;
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
                entry.put("offset",local.getOffset().toString());entry.put("price",price.toPlainString());
                try {
                    BigDecimal low=field(row,"low"),high=field(row,"high"),closing=field(row,"close");
                    if(low.compareTo(high)<=0 && price.compareTo(low)>=0 && price.compareTo(high)<=0 && closing.compareTo(low)>=0 && closing.compareTo(high)<=0) {entry.put("low",low);entry.put("high",high);entry.put("close",closing);}
                } catch(RuntimeException incomplete) { /* Old open-only feeds remain usable in advanced mode. */ }
                result.put(time,entry);
            }catch(NumberFormatException | ArithmeticException | BusinessException ignored){ }
        }
        return result;
    }
    public Map<String,Object> quote(TradingSymbol symbol,long open,long close) {
        Map<String,Object> result=new LinkedHashMap<>();
        Map<String,Object> opening=window(symbol,open);
        Map<String,Object> closing=open==close?opening:window(symbol,close);
        // Queue all exact-minute sources before checking readiness; one loading source must not block the others.
        Map<String,Object> openConversion=currencyWindow(symbol.getQuoteCurrency(),symbol.getMarketSource(),open);
        Map<String,Object> closeConversion=open==close?openConversion:currencyWindow(symbol.getQuoteCurrency(),symbol.getMarketSource(),close);
        boolean cross=FxContractRules.isForex(symbol) && !"USD".equals(symbol.getBaseCurrency()) && !"USD".equals(symbol.getQuoteCurrency());
        Map<String,Object> baseConversion=cross?currencyWindow(symbol.getBaseCurrency(),symbol.getMarketSource(),open):null;
        result.put("openPrice",exact(opening,open));result.put("closePrice",exact(closing,close));
        result.put("openRate",currencyRate(symbol.getQuoteCurrency(),symbol.getMarketSource(),open,openConversion));
        result.put("closeRate",currencyRate(symbol.getQuoteCurrency(),symbol.getMarketSource(),close,closeConversion));
        BigDecimal openingPrice=(BigDecimal)result.get("openPrice");
        result.put("marginRate", !FxContractRules.isForex(symbol) ? openingPrice.multiply((BigDecimal)result.get("openRate"))
            : "USD".equals(symbol.getBaseCurrency()) ? BigDecimal.ONE : "USD".equals(symbol.getQuoteCurrency()) ? openingPrice
            : currencyRate(symbol.getBaseCurrency(),symbol.getMarketSource(),open,baseConversion));
        result.put("source",symbol.getMarketSource());result.put("priceBasis","EXACT_MINUTE_OPEN");
        result.put("openMinute",open);result.put("closeMinute",close);
        return result;
    }
    /** Batch windows rather than one network request per candidate pair. Missing rates stay missing. */
    public NavigableMap<Long,ManualOrderGenerator.Candle> generationCandles(TradingSymbol symbol,long from,long to) {return generationCandles(symbol,from,to,false);}
    private NavigableMap<Long,ManualOrderGenerator.Candle> generationCandles(TradingSymbol symbol,long from,long to,boolean requireReady) {
        if(to<=from || to-from>ManualOrderGenerator.RANGE+60000)throw new BusinessException("生成搜索范围最多30天");
        NavigableMap<Long,ManualOrderGenerator.Candle> result=new TreeMap<>();
        QuoteCurrencyConversion conversion=QuoteCurrencyConversion.fixed(symbol.getQuoteCurrency())?null:QuoteCurrencyConversion.route(symbol.getQuoteCurrency(),symbol.getMarketSource());
        if(!QuoteCurrencyConversion.fixed(symbol.getQuoteCurrency()) && conversion==null)throw new BusinessException("缺少历史换算率");
        QuoteCurrencyConversion baseConversion=FxContractRules.isForex(symbol) && !"USD".equals(symbol.getBaseCurrency()) && !"USD".equals(symbol.getQuoteCurrency())
            ? QuoteCurrencyConversion.route(symbol.getBaseCurrency(),symbol.getMarketSource()) : null;
        boolean pending=false;
        for(long cursor=Math.floorDiv(from,WINDOW)*WINDOW;cursor<to;cursor+=WINDOW) {
            Map<String,Object> primaryResponse=window(symbol,cursor),rateResponse=currencyWindow(symbol.getQuoteCurrency(),symbol.getMarketSource(),cursor);
            Map<String,Object> baseResponse=baseConversion==null?Collections.emptyMap():currencyWindow(symbol.getBaseCurrency(),symbol.getMarketSource(),cursor);
            for(Map<String,Object> response:Arrays.asList(primaryResponse,rateResponse,baseResponse)){Object state=response.get("data");pending|=state instanceof Map && Boolean.TRUE.equals(((Map<?,?>)state).get("pending"));}
            SortedMap<Long,Map<String,Object>> primary=selectMinutes(primaryResponse,from,to,System.currentTimeMillis(),ZoneOffset.UTC);
            SortedMap<Long,Map<String,Object>> rates=conversion==null?null:selectMinutes(rateResponse,from,to,System.currentTimeMillis(),ZoneOffset.UTC);
            SortedMap<Long,Map<String,Object>> baseRates=baseConversion==null?null:selectMinutes(baseResponse,from,to,System.currentTimeMillis(),ZoneOffset.UTC);
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
                if(value.signum()>0)result.put(e.getKey(),new ManualOrderGenerator.Candle(e.getKey(),price,value,marginRate,(BigDecimal)e.getValue().get("low"),(BigDecimal)e.getValue().get("high"),(BigDecimal)e.getValue().get("close")));
            }
        }
        if(requireReady) {
            result.values().removeIf(c->c.low==null || c.high==null || c.closePrice==null || c.time+60000>System.currentTimeMillis());
            if(pending && result.isEmpty())throw new BusinessException(HISTORY_LOADING,"所需分钟行情或换算率尚未就绪，正在后台补齐；请稍后重试，未创建订单或修改资金");
        }
        return result;
    }
    private static BigDecimal field(Map<?,?> row,String name) {
        return ManualOrderCalculation.positive(new BigDecimal(String.valueOf(row.containsKey(name+"_price")?row.get(name+"_price"):row.get(name))),"K线"+name);
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public NavigableMap<Long,ManualOrderGenerator.Candle> simpleCandles(TradingSymbol symbol,long from,long to) {
        // A single history pass; never wait for unrelated missing minutes when a complete candidate is already available.
        return generationCandles(symbol,from,to,true);
    }
    /** Selected chart times need two exact candles, not thirty days. Rates still use the shared UTC-window cache. */
    @org.springframework.transaction.annotation.Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public NavigableMap<Long,ManualOrderGenerator.Candle> selectedCandles(TradingSymbol symbol,long open,long close) {
        Map<Long,Map<String,Object>> primary=new LinkedHashMap<>(),rates=new HashMap<>(),baseRates=new HashMap<>();
        Map<Long,Long> windows=new HashMap<>();
        boolean cross=FxContractRules.isForex(symbol) && !"USD".equals(symbol.getBaseCurrency()) && !"USD".equals(symbol.getQuoteCurrency());
        for(long time:new long[]{open,close}) {
            if(!primary.containsKey(time))primary.put(time,market.historicalKline(symbol.getSymbol(),"1m",1,time+59999));
            long window=windowEnd(time);windows.put(time,window);
            if(!rates.containsKey(window))rates.put(window,currencyWindow(symbol.getQuoteCurrency(),symbol.getMarketSource(),time));
            if(cross && !baseRates.containsKey(window))baseRates.put(window,currencyWindow(symbol.getBaseCurrency(),symbol.getMarketSource(),time));
        }
        NavigableMap<Long,ManualOrderGenerator.Candle> result=new TreeMap<>();
        for(Map.Entry<Long,Map<String,Object>> e:primary.entrySet()) {
            long time=e.getKey();Map<?,?> row=rangeCandle(e.getValue(),time);
            if(time%60000!=0 || time+60000>System.currentTimeMillis())throw new BusinessException("只能使用已结束的整分钟OHLC行情");
            BigDecimal price=minutePrice(row),rate=currencyRate(symbol.getQuoteCurrency(),symbol.getMarketSource(),time,rates.get(windows.get(time)));
            BigDecimal margin=!FxContractRules.isForex(symbol)?price.multiply(rate):"USD".equals(symbol.getBaseCurrency())?BigDecimal.ONE:"USD".equals(symbol.getQuoteCurrency())?price:currencyRate(symbol.getBaseCurrency(),symbol.getMarketSource(),time,baseRates.get(windows.get(time)));
            result.put(time,new ManualOrderGenerator.Candle(time,price,rate,margin,field(row,"low"),field(row,"high"),field(row,"close")));
        }
        return result;
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> rangeQuote(TradingSymbol symbol,long open,long close,BigDecimal p0,BigDecimal p1) {
        NavigableMap<Long,ManualOrderGenerator.Candle> candles=selectedCandles(symbol,open,close);
        ManualOrderGenerator.Candle a=candles.get(open),b=candles.get(close);
        if(p0.compareTo(a.low)<0 || p0.compareTo(a.high)>0 || p1.compareTo(b.low)<0 || p1.compareTo(b.high)>0)throw new BusinessException("订单价格不在对应K线高低价范围内，请重新生成");
        Map<String,Object> result=new LinkedHashMap<>();result.put("openPrice",p0);result.put("closePrice",p1);
        result.put("openRate",a.rate);result.put("closeRate",b.rate);result.put("marginRate",SimpleManualOrderGenerator.marginRate(symbol,a,p0));
        result.put("source",symbol.getMarketSource());result.put("priceBasis","SIMPLE_OHLC_RANGE");result.put("openMinute",open);result.put("closeMinute",close);
        result.put("openLow",a.low);result.put("openHigh",a.high);result.put("closeLow",b.low);result.put("closeHigh",b.high);return result;
    }
    private Map<?,?> rangeCandle(Map<String,Object> response,long minute) {
        exact(response,minute); // Preserve the existing loading/unavailable error contract.
        Object data=response.get("data"),rows=data instanceof Map?((Map<?,?>)data).get("kline_list"):null;
        if(rows instanceof List)for(Object item:(List<?>)rows)if(item instanceof Map) {
            Map<?,?> row=(Map<?,?>)item;
            try {
                long t=new BigDecimal(String.valueOf(row.get("timestamp"))).longValueExact();if(t<100000000000L)t*=1000;
                if(t==minute) {
                    BigDecimal low=field(row,"low"),high=field(row,"high"),opening=minutePrice(row),closing=field(row,"close");
                    if(low.compareTo(high)>0 || opening.compareTo(low)<0 || opening.compareTo(high)>0 || closing.compareTo(low)<0 || closing.compareTo(high)>0)throw new BusinessException("分钟OHLC行情无效");
                    return row;
                }
            }catch(NumberFormatException | ArithmeticException invalid) { }
        }
        throw new BusinessException("该分钟缺少完整OHLC行情，不能进行价格区间匹配");
    }
    private BigDecimal currencyRate(String currency,String source,long minute,Map<String,Object> response) {
        if(QuoteCurrencyConversion.fixed(currency)) return BigDecimal.ONE;
        QuoteCurrencyConversion r=QuoteCurrencyConversion.route(currency,source);
        if(r==null) throw new BusinessException("缺少历史换算率");
        return ManualOrderCalculation.positive(exact(response,minute).multiply(r.scale),"历史换算率");
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
        throw new BusinessException(Boolean.TRUE.equals(state.get("pending"))?HISTORY_LOADING:400,reason+"（"+state.getOrDefault("code",null)+" / "+Instant.ofEpochMilli(minute)+"）；不使用邻近价格");
    }
    private static BigDecimal minutePrice(Map<?,?> row) {
        BigDecimal price=new BigDecimal(String.valueOf(row.containsKey("open_price")?row.get("open_price"):row.get("open")));
        // Use the same ledger precision for the picker, generator and authoritative preview.
        if(price.scale()>16)price=price.setScale(16,java.math.RoundingMode.HALF_UP);
        return ManualOrderCalculation.positive(price,"分钟起点价格");
    }
}
