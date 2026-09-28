package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

@Service @RequiredArgsConstructor
public class ManualOrderPrices {
    private final ForexQuoteMarketService market;
    public Map<String,Object> quote(TradingSymbol symbol,long open,long close) {
        Map<String,Object> result=new LinkedHashMap<>();
        Map<String,Object> opening=market.historicalKline(symbol.getSymbol(),"1m",2,open+59999);
        Map<String,Object> closing=open==close?opening:market.historicalKline(symbol.getSymbol(),"1m",2,close+59999);
        result.put("openPrice",exact(opening,open));result.put("closePrice",exact(closing,close));
        result.put("openRate",rate(symbol,open));result.put("closeRate",rate(symbol,close));
        result.put("source",symbol.getMarketSource());result.put("priceBasis","EXACT_MINUTE_OPEN");
        result.put("openMinute",open);result.put("closeMinute",close);
        return result;
    }
    private BigDecimal rate(TradingSymbol s,long minute) {
        if(QuoteCurrencyConversion.fixed(s.getQuoteCurrency())) return BigDecimal.ONE;
        QuoteCurrencyConversion r=QuoteCurrencyConversion.route(s.getQuoteCurrency(),s.getMarketSource());
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
                if(time==minute) return ManualOrderCalculation.positive(new BigDecimal(String.valueOf(row.containsKey("open_price")?row.get("open_price"):row.get("open"))),"分钟起点价格");
            } catch(NumberFormatException | ArithmeticException ignored) { }
        }
        throw new BusinessException("缺少所选分钟起点行情，请等待后重新预览；不使用邻近价格");
    }
}
