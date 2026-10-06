package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimpleManualOrderPerformanceTest {
    static BigDecimal n(String v){return new BigDecimal(v);}
    static TradingSymbol symbol(){TradingSymbol s=new TradingSymbol();s.setLotSize(n("1"));s.setFeeMultiplier(n("0.01"));s.setQuoteCurrency("USD");s.setSourceCategory("US");return s;}
    static NavigableMap<Long,ManualOrderGenerator.Candle> flat(int size){NavigableMap<Long,ManualOrderGenerator.Candle> out=new TreeMap<>();for(int i=1;i<=size;i++){long time=i*60000L;out.put(time,new ManualOrderGenerator.Candle(time,n("100"),BigDecimal.ONE,n("100"),n("100"),n("100"),n("100")));}return out;}
    @Test void fixedPricesWithAnImpossiblePositiveTargetAndFreeQuantity(){
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.openPrice=n("100");r.closePrice=n("100");r.targetNet=n("55222");
        long start=System.nanoTime();assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.solve(r,flat(500),symbol(),BigDecimal.ZERO,n("100")));
        System.out.println("SIMPLE_SPEED fixed-flat-500 "+(System.nanoTime()-start)/1000000.0+" ms");
    }
    @Test void manualCloseWithNoOverlappingAutomaticOpeningBand(){
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.closePrice=n("100");r.targetNet=n("55222");
        long start=System.nanoTime();assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.solve(r,flat(500),symbol(),BigDecimal.ZERO,n("100")));
        System.out.println("SIMPLE_SPEED disjoint-500 "+(System.nanoTime()-start)/1000000.0+" ms");
    }
    @Test void fullSevenDaysDisjointAndQuantityBoundFailuresStaySubsecond() {
        NavigableMap<Long,ManualOrderGenerator.Candle> candles=flat(10080);
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.closePrice=n("100");r.targetNet=n("55222");
        long start=System.nanoTime();assertTimeout(java.time.Duration.ofSeconds(1),()->assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.solve(r,candles,symbol(),BigDecimal.ZERO,n("100"))));
        System.out.println("SIMPLE_SPEED disjoint-10080 "+(System.nanoTime()-start)/1000000.0+" ms");
        for(long time:new ArrayList<>(candles.keySet()))candles.put(time,new ManualOrderGenerator.Candle(time,n("100"),BigDecimal.ONE,n("100"),n("99"),n("101"),n("100")));
        r.quantity=n("1");start=System.nanoTime();assertTimeout(java.time.Duration.ofSeconds(2),()->assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.solve(r,candles,symbol(),BigDecimal.ZERO,n("100"))));
        System.out.println("SIMPLE_SPEED impossible-quantity-10080 "+(System.nanoTime()-start)/1000000.0+" ms");
    }
    @Test void fixedMinutePairCalculatesTheNearestLegalLotsAndActualNet() {
        NavigableMap<Long,ManualOrderGenerator.Candle> rows=flat(10080);long a=100*60000L,b=101*60000L;
        rows.put(a,new ManualOrderGenerator.Candle(a,n("99.5"),BigDecimal.ONE,n("99.5"),n("99.4"),n("99.6"),n("99.5")));
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.openTime=a;r.closeTime=b;r.openPrice=n("99.5");r.closePrice=n("100");r.targetNet=n("55222");
        long start=System.nanoTime();SimpleManualOrderGenerator.Candidate c=assertTimeout(java.time.Duration.ofSeconds(1),()->SimpleManualOrderGenerator.solve(r,rows,symbol(),BigDecimal.ZERO,n("100")));
        assertEquals(a,c.open.time);assertEquals(b,c.close.time);assertEquals(0,n("112697.96").compareTo(c.calculation.get("quantity")));assertEquals(0,n("55222.0004").compareTo(c.calculation.get("net")));
        assertTrue(SimpleManualOrderGenerator.matches(c.calculation.get("net"),r.targetNet,true,n("5")));
        for(String q:Arrays.asList("112697.95","112697.97"))assertTrue(c.calculation.get("net").subtract(r.targetNet).abs().compareTo(n("0.49").multiply(n(q)).subtract(r.targetNet).abs())<0);
        System.out.println("SIMPLE_SPEED pinned-pair-10080 "+(System.nanoTime()-start)/1000000.0+" ms");
    }
    @Test void fixedPricesAndCoarseStepsRejectBeforeOpeningPairSearch() {
        NavigableMap<Long,ManualOrderGenerator.Candle> rows=flat(10080);TradingSymbol s=symbol();
        s.setQuantityUnitType("LOT");s.setBaseCurrency("TEST");s.setSpecVersion(1L);s.setQuantityStep(BigDecimal.ONE);s.setMinOrderQuantity(BigDecimal.ONE);s.setMinOrderNotional(BigDecimal.ZERO);
        for(long time:new ArrayList<>(rows.keySet()))rows.put(time,new ManualOrderGenerator.Candle(time,n("100"),BigDecimal.ONE,n("100"),n("99"),n("101"),n("100")));
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.openPrice=n("99.5");r.closePrice=n("100");r.targetNet=n("0.6");r.netTolerance=n("5");
        long start=System.nanoTime();assertTimeout(java.time.Duration.ofSeconds(2),()->assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.solve(r,rows,s,BigDecimal.ZERO,n("100"))));
        System.out.println("SIMPLE_SPEED coarse-step-10080 "+(System.nanoTime()-start)/1000000.0+" ms");
    }
    @Test void feeDominatedAndZeroTargetFailuresDoNotEnumerateMillionsOfPairs() {
        NavigableMap<Long,ManualOrderGenerator.Candle> rows=flat(10080);
        for(long time:new ArrayList<>(rows.keySet()))rows.put(time,new ManualOrderGenerator.Candle(time,n("100"),BigDecimal.ONE,n("100"),n("99"),n("101"),n("100")));
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.closePrice=n("100");r.targetNet=n("55222");TradingSymbol s=symbol();s.setFeeMultiplier(n("3"));
        long start=System.nanoTime();assertTimeout(java.time.Duration.ofSeconds(1),()->assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.solve(r,rows,s,BigDecimal.ZERO,n("100"))));System.out.println("SIMPLE_SPEED fee-dominated-10080 "+(System.nanoTime()-start)/1000000.0+" ms");
        for(long time:new ArrayList<>(rows.keySet()))rows.put(time,new ManualOrderGenerator.Candle(time,n("100"),BigDecimal.ONE,n("100"),n("99.6"),n("101"),n("100")));
        r.targetNet=BigDecimal.ZERO;r.side="BUY";s.setFeeMultiplier(n("0.45"));start=System.nanoTime();assertTimeout(java.time.Duration.ofSeconds(1),()->assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.solve(r,rows,s,BigDecimal.ZERO,n("100"))));System.out.println("SIMPLE_SPEED zero-outside-OHLC-10080 "+(System.nanoTime()-start)/1000000.0+" ms");
    }
    @Test void overlappingNotionalFailuresReturnAnHonestBoundedSearchError() {
        NavigableMap<Long,ManualOrderGenerator.Candle> rows=flat(10080);TradingSymbol s=symbol();
        s.setQuantityUnitType("LOT");s.setBaseCurrency("TEST");s.setSpecVersion(1L);s.setQuantityStep(n("0.01"));s.setMinOrderQuantity(n("0.01"));s.setMinOrderNotional(n("1000000"));
        for(long time:new ArrayList<>(rows.keySet()))rows.put(time,new ManualOrderGenerator.Candle(time,n("100"),BigDecimal.ONE,n("100"),n("99"),n("101"),n("100")));
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.openPrice=n("99.5");r.closePrice=n("100");r.quantity=BigDecimal.ONE;r.targetNet=n("0.49");r.side="BUY";
        long start=System.nanoTime();BusinessException e=assertTimeout(java.time.Duration.ofSeconds(3),()->assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.solve(r,rows,s,BigDecimal.ZERO,n("100"))));
        assertEquals(400,e.getCode());assertTrue(e.getMessage().contains("匹配范围过大"));assertFalse(e.getMessage().contains("没有符合"));
        System.out.println("SIMPLE_SPEED overlapping-search-budget-10080 "+(System.nanoTime()-start)/1000000.0+" ms");
    }
}
