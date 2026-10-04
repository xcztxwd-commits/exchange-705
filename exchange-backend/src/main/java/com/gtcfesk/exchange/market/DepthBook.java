package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.util.*;

/** A complete, limited-depth snapshot. No incremental merge and no synthetic price levels. */
final class DepthBook {
    final String sequence, transport;
    final Long sourceAsOf;
    final long receivedAt;
    final List<BigDecimal[]> bids, asks;
    DepthBook(String sequence, Long sourceAsOf, long receivedAt, String transport,
              List<BigDecimal[]> bids, List<BigDecimal[]> asks) {
        this.sequence=sequence; this.sourceAsOf=sourceAsOf; this.receivedAt=receivedAt;
        this.transport=transport; this.bids=bids; this.asks=asks;
    }
    static DepthBook parse(JsonNode row, boolean okx, boolean futures, boolean ws, long at) {
        String sequence=row.path(okx?"seqId":ws&&futures?"u":"lastUpdateId").asText();
        if(!sequence.matches("[0-9]{1,30}")) throw invalid("invalid_sequence");
        JsonNode time=row.path(okx?"ts":"T");
        if(time.isMissingNode()&&futures)time=row.path("E");
        Long sourceAsOf=null;
        if(!time.isMissingNode()) {
            try { sourceAsOf=Long.valueOf(time.asText()); }
            catch(NumberFormatException e){throw invalid("invalid_source_time");}
            if(sourceAsOf<=0||sourceAsOf>at+60000)throw invalid("invalid_source_time");
        }
        if((okx||futures)&&sourceAsOf==null)throw invalid("missing_source_time");
        if(okx&&ws&&(row.path("bids").size()>5||row.path("asks").size()>5))throw invalid("invalid_depth");
        List<BigDecimal[]> bids=side(row.path(ws&&futures&&!okx?"b":"bids"),true);
        List<BigDecimal[]> asks=side(row.path(ws&&futures&&!okx?"a":"asks"),false);
        if(!bids.isEmpty()&&!asks.isEmpty()&&bids.get(0)[0].compareTo(asks.get(0)[0])>0)
            throw invalid("crossed_book");
        return new DepthBook(sequence,sourceAsOf,at,ws?"WS_SNAPSHOT":"REST_POLL",bids,asks);
    }
    private static List<BigDecimal[]> side(JsonNode rows,boolean descending) {
        if(!rows.isArray()||rows.size()>20)throw invalid("invalid_depth");
        NavigableMap<BigDecimal,BigDecimal> prices=new TreeMap<>();
        for(JsonNode row:rows) {
            if(!row.isArray()||row.size()<2)throw invalid("invalid_level");
            BigDecimal price=decimal(row.path(0)),quantity=decimal(row.path(1));
            if(price.signum()<=0||quantity.signum()<0||prices.containsKey(price))throw invalid("invalid_level");
            prices.put(price,quantity);
        }
        List<BigDecimal[]> result=new ArrayList<>();
        (descending?prices.descendingMap():prices).forEach((p,q)->{if(q.signum()>0)result.add(new BigDecimal[]{p,q});});
        return Collections.unmodifiableList(result);
    }
    static BigDecimal decimal(JsonNode value) {
        if(!value.isTextual()||value.asText().length()>96)throw invalid("invalid_decimal");
        try {BigDecimal d=new BigDecimal(value.asText());
            if(d.precision()>60||Math.abs(d.scale())>30)throw invalid("invalid_decimal");return d;
        }catch(NumberFormatException e){throw invalid("invalid_decimal");}
    }
    static String text(BigDecimal d){return d==null?null:d.stripTrailingZeros().toPlainString();}
    boolean sameContent(DepthBook other){return levels(bids,20).equals(levels(other.bids,20))&&levels(asks,20).equals(levels(other.asks,20));}
    static List<Map<String,String>> levels(List<BigDecimal[]> side,int limit) {
        List<Map<String,String>> result=new ArrayList<>();BigDecimal sum=BigDecimal.ZERO;
        for(BigDecimal[] row:side.subList(0,Math.min(limit,side.size()))){sum=sum.add(row[1]);
            Map<String,String> level=new LinkedHashMap<>();level.put("price",text(row[0]));
            level.put("quantity",text(row[1]));level.put("cumulativeQuantity",text(sum));result.add(level);}
        return result;
    }
    static BigDecimal total(List<BigDecimal[]> side,int limit){BigDecimal sum=BigDecimal.ZERO;for(int i=0;i<Math.min(limit,side.size());i++)sum=sum.add(side.get(i)[1]);return sum;}
    Map<String,Object> view(int limit){Map<String,Object> result=new LinkedHashMap<>();
        result.put("bids",levels(bids,limit));result.put("asks",levels(asks,limit));
        Map<String,Integer> counts=new LinkedHashMap<>();counts.put("bids",Math.min(limit,bids.size()));counts.put("asks",Math.min(limit,asks.size()));
        result.put("displayedLevels",counts);Map<String,Integer> available=new LinkedHashMap<>();available.put("bids",bids.size());available.put("asks",asks.size());result.put("availableLevels",available);
        BigDecimal b=total(bids,limit),a=total(asks,limit),sum=b.add(a);
        BigDecimal ratio=sum.signum()==0||bids.isEmpty()||asks.isEmpty()?null:b.divide(sum,8,RoundingMode.HALF_UP);
        result.put("bidQuantity",text(b));result.put("askQuantity",text(a));result.put("bidRatio",text(ratio));
        result.put("askRatio",ratio==null?null:text(BigDecimal.ONE.subtract(ratio)));
        result.put("spread",bids.isEmpty()||asks.isEmpty()?null:text(asks.get(0)[0].subtract(bids.get(0)[0])));
        result.put("sequence",sequence);result.put("sourceAsOf",sourceAsOf);result.put("receivedAt",receivedAt);
        result.put("sourceTimeBasis",sourceAsOf==null?"NOT_PROVIDED":"PROVIDER");result.put("refreshMethod",transport);return result;
    }
    static MarketHttp.Failure invalid(String reason){return new MarketHttp.Failure(reason,0);}
}
