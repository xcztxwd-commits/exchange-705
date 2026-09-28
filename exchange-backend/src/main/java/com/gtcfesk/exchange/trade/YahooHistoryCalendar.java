package com.gtcfesk.exchange.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.MarketHttp;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLEncoder;
import java.time.*;
import java.util.*;

/** Request-time Yahoo coverage only. No persisted calendar, no inferred market sessions. */
@Service @RequiredArgsConstructor
public class YahooHistoryCalendar {
    private final MarketHttp http;
    private final ObjectMapper json;
    @Value("${market.quote.yahoo-url:https://query1.finance.yahoo.com/v8/finance}") private String base;
    public Map<String,Object> page(TradingSymbol symbol,String month,String timezone,int page) {
        if(!"yahoo".equalsIgnoreCase(symbol.getMarketSource()))throw new BusinessException("该日历仅支持 Yahoo 品种");
        try {
            YearMonth ym=YearMonth.parse(month);ZoneId zone=ZoneId.of(timezone);
            if(page<0 || page>6)throw new BusinessException("日历页码无效");
            LocalDate first=ym.atDay(1).plusDays(page*5L),end=ym.plusMonths(1).atDay(1);
            long now=System.currentTimeMillis(),from=first.atStartOfDay(zone).toInstant().toEpochMilli();
            Map<String,Object> out=new LinkedHashMap<>();out.put("minutes",Collections.emptyList());
            if(!first.isBefore(end) || from>now)return out;
            long to=Math.min(now,(first.plusDays(5).isBefore(end)?first.plusDays(5):end).atStartOfDay(zone).toInstant().toEpochMilli());
            String code=symbol.getAlltickSymbol()==null?symbol.getSymbol():symbol.getAlltickSymbol();
            String url=base+"/chart/"+URLEncoder.encode(code,"UTF-8")+"?interval=1m&includePrePost=false&period1="+from/1000+"&period2="+to/1000;
            JsonNode root=json.readTree(http.get(URI.create(url)).getBody());
            if(!root.path("chart").path("error").isNull() && !root.path("chart").path("error").isMissingNode())throw new BusinessException("Yahoo 未提供该历史范围的分钟数据");
            JsonNode result=root.path("chart").path("result").path(0);
            if(result.isMissingNode() || result.isNull())throw new BusinessException("Yahoo 历史日历响应无效，请重试");
            out.put("minutes",parse(result,from,to,now,zone));return out;
        } catch(BusinessException e){throw e;}
        catch(DateTimeException e){throw new BusinessException("月份或时区无效");}
        catch(Exception e){throw new BusinessException("Yahoo 历史日历获取失败或已超出分钟数据保留范围；未验证日期保持禁用");}
    }
    static List<Map<String,Object>> parse(JsonNode result,long from,long to,long now,ZoneId zone) {
        List<Map<String,Object>> candles=new ArrayList<>();
        JsonNode timestamps=result.path("timestamp"),prices=result.path("indicators").path("quote").path(0).path("open");
        for(int i=0;i<timestamps.size();i++){
            Map<String,Object> row=new HashMap<>();row.put("timestamp",timestamps.path(i).asText());row.put("open",prices.path(i).asText());candles.add(row);
        }
        Map<String,Object> response=Collections.singletonMap("data",Collections.singletonMap("kline_list",candles));
        return new ArrayList<>(ManualOrderPrices.selectMinutes(response,from,to,now,zone).values());
    }
}
