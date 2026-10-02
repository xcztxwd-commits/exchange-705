package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SimpleManualOrderGeneratorTest {
    static BigDecimal n(String v){return new BigDecimal(v);}
    static TradingSymbol symbol() {
        TradingSymbol s=new TradingSymbol();s.setLotSize(n("1"));s.setFeeMultiplier(n("0.01"));s.setQuoteCurrency("USD");s.setSourceCategory("US");return s;
    }
    static ManualOrderGenerator.Candle candle(long minute,String open,String low,String high) {
        return new ManualOrderGenerator.Candle(minute*60000,n(open),BigDecimal.ONE,n(open),n(low),n(high),n(open));
    }
    static NavigableMap<Long,ManualOrderGenerator.Candle> candles() {
        NavigableMap<Long,ManualOrderGenerator.Candle> c=new TreeMap<>();
        for(ManualOrderGenerator.Candle item:Arrays.asList(candle(1,"99.5","99.2","99.7"),candle(2,"99.6","99.4","99.7"),candle(3,"100","99.9","100.1")))c.put(item.time,item);
        return c;
    }
    static SimpleManualOrderGenerator.Candidate solve(SimpleManualOrderGenerator.Request r) {return SimpleManualOrderGenerator.solve(r,candles(),symbol(),BigDecimal.ZERO,n("100"));}
    @Test void symbolOnlyUsesOneLotLatestMinuteAndStrictOrdering() {
        SimpleManualOrderGenerator.Candidate c=solve(new SimpleManualOrderGenerator.Request());
        assertEquals(120000,c.open.time);assertEquals(180000,c.close.time);assertEquals(0,c.calculation.get("quantity").compareTo(BigDecimal.ONE));assertTrue(c.open.time<c.close.time);assertTrue(c.calculation.get("net").signum()>0);
    }
    @Test void manualPriceInsideOhlcIsNotReplacedByOpen() {
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.openPrice=n("99.67");r.closePrice=n("100.03");r.quantity=n("150");r.side="BUY";
        SimpleManualOrderGenerator.Candidate c=solve(r);assertEquals(r.openPrice,c.openPrice);assertEquals(r.closePrice,c.closePrice);assertEquals(r.quantity,c.calculation.get("quantity"));
    }
    @Test void manualPriceOutsideAutomaticBandIsAllowed() {
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.openPrice=n("99.9");r.closePrice=n("100");
        NavigableMap<Long,ManualOrderGenerator.Candle> rows=candles();rows.put(120000L,candle(2,"99.9","99.8","100"));
        assertEquals(r.openPrice,SimpleManualOrderGenerator.solve(r,rows,symbol(),BigDecimal.ZERO,n("100")).openPrice);
    }
    @Test void automaticQuantityIsNotCappedBySlider() {
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.targetNet=n("5999");
        SimpleManualOrderGenerator.Candidate c=solve(r);assertTrue(c.calculation.get("quantity").compareTo(n("100"))>0);assertTrue(SimpleManualOrderGenerator.matches(c.calculation.get("net"),r.targetNet,true,r.netTolerance));
        BigDecimal amplitude=c.closePrice.subtract(c.openPrice).abs().divide(c.closePrice);assertTrue(amplitude.compareTo(n("0.003"))>=0 && amplitude.compareTo(n("0.008"))<=0);
    }
    @Test void newestAcceptableProfitWinsOverOlderExactProfit() {
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.quantity=n("100");r.targetNet=n("39");
        NavigableMap<Long,ManualOrderGenerator.Candle> rows=candles();rows.put(60000L,candle(1,"99.6","99.6","99.6"));rows.put(120000L,candle(2,"99.61","99.61","99.62"));
        SimpleManualOrderGenerator.Candidate latest=SimpleManualOrderGenerator.solve(r,rows,symbol(),BigDecimal.ZERO,n("100"));
        assertEquals(120000,latest.open.time);assertEquals(0,n("38").compareTo(latest.calculation.get("net")));
        r.allowNetAdjustment=false;assertEquals(60000,SimpleManualOrderGenerator.solve(r,rows,symbol(),BigDecimal.ZERO,n("100")).open.time);
    }
    @Test void strictModeNeverAcceptsRoundedDisplayEquality() {
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.quantity=n("100");r.openPrice=n("99.5");r.targetNet=n("49.0000000000000001");r.allowNetAdjustment=false;
        assertThrows(BusinessException.class,()->solve(r));r.targetNet=n("49");assertEquals(0,solve(r).calculation.get("net").compareTo(r.targetNet));
    }
    @Test void strictQuantityFreeSearchFindsDecimalFriendlySolution() {
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.targetNet=n("5999");r.allowNetAdjustment=false;
        assertEquals(0,solve(r).calculation.get("net").compareTo(r.targetNet));
    }
    @Test void negativeAndZeroTargetsAreCalculatedNotFabricated() {
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.targetNet=n("-10.2");r.quantity=n("20");r.side="SELL";r.allowNetAdjustment=false;
        assertEquals(0,solve(r).calculation.get("net").compareTo(r.targetNet));
        r.targetNet=BigDecimal.ZERO;r.side="BUY";TradingSymbol s=symbol();s.setFeeMultiplier(n("0.5"));
        assertEquals(0,SimpleManualOrderGenerator.solve(r,candles(),s,BigDecimal.ZERO,n("100")).calculation.get("net").signum());
    }
    @Test void toleranceBoundariesAreExactAndSigned() {
        assertTrue(SimpleManualOrderGenerator.matches(n("95"),n("100"),true,n("5")));
        assertFalse(SimpleManualOrderGenerator.matches(n("94.9999999999999999"),n("100"),true,n("5")));
        assertTrue(SimpleManualOrderGenerator.matches(n("-105"),n("-100"),true,n("5")));
        assertFalse(SimpleManualOrderGenerator.matches(n("0.0000000000000001"),BigDecimal.ZERO,true,n("99")));
    }
    @Test void quantityStepAndLeverageAreHardRules() {
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.quantity=n("1.001");assertThrows(BusinessException.class,()->solve(r));
        r.quantity=null;r.leverage=n("101");assertThrows(BusinessException.class,()->solve(r));r.leverage=null;
        assertEquals(0,SimpleManualOrderGenerator.solve(r,candles(),symbol(),BigDecimal.ZERO,n("50")).leverage.compareTo(n("50")));
    }
    @Test void fullyFixedImpossibleSevenDaySearchDoesNotScanEveryMinutePair() {
        NavigableMap<Long,ManualOrderGenerator.Candle> rows=new TreeMap<>();
        for(int i=1;i<=10080;i++){ManualOrderGenerator.Candle c=candle(i,"100","99.9","100.1");rows.put(c.time,c);}
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.openPrice=n("100");r.closePrice=n("100");r.quantity=n("100");r.targetNet=n("5999");
        assertTimeout(java.time.Duration.ofSeconds(2),()->assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.solve(r,rows,symbol(),BigDecimal.ZERO,n("100"))));
    }
    @Test void chartTimesPinExactCandlesEvenWhenTheSamePricesOccurLater() {
        NavigableMap<Long,ManualOrderGenerator.Candle> rows=candles();rows.put(240000L,candle(4,"100","99.9","100.1"));
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.openPrice=n("99.5");r.closePrice=n("100");r.openTime=60000L;r.closeTime=180000L;
        SimpleManualOrderGenerator.Candidate selected=SimpleManualOrderGenerator.solve(r,rows,symbol(),BigDecimal.ZERO,n("100"));
        assertEquals(60000,selected.open.time);assertEquals(180000,selected.close.time);assertEquals(r.openPrice,selected.openPrice);assertEquals(r.closePrice,selected.closePrice);
        r.closeTime=null;assertEquals(240000,SimpleManualOrderGenerator.solve(r,rows,symbol(),BigDecimal.ZERO,n("100")).close.time);
    }
    @Test void chartClosingTimeOverridesAutomaticLatestCloseWithoutOverwritingPrices() {
        NavigableMap<Long,ManualOrderGenerator.Candle> rows=candles();rows.put(240000L,candle(4,"120","119.9","120.1"));
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.openTime=60000L;r.closeTime=180000L;
        SimpleManualOrderGenerator.Candidate c=SimpleManualOrderGenerator.solve(r,rows,symbol(),BigDecimal.ZERO,n("100"));assertEquals(180000,c.close.time);assertEquals(0,n("100").compareTo(c.closePrice));
        r.openPrice=n("101");assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.solve(r,rows,symbol(),BigDecimal.ZERO,n("100")));
    }
    @Test void chartRangeRejectsSameReversedUnfinishedAndOutOfWindowMinutes() {
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.openTime=60000L;r.closeTime=60000L;
        assertThrows(BusinessException.class,()->solve(r));r.openTime=180000L;r.closeTime=60000L;assertThrows(BusinessException.class,()->solve(r));
        r.openTime=60001L;r.closeTime=180000L;assertThrows(BusinessException.class,()->solve(r));
        r.openTime=0L;assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.validateTimes(r,60000,240000));
        r.openTime=60000L;r.closeTime=240000L;assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.validateTimes(r,60000,240000));
    }
    @Test void multipleFixedFieldsMatrixPreservesEveryInput() {
        for(String direction:Arrays.asList("BUY","SELL"))for(int mask=0;mask<32;mask++) {
            SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.side=direction;
            if((mask&1)!=0)r.openPrice=n("99.5");if((mask&2)!=0)r.closePrice=n("100");if((mask&4)!=0)r.quantity=n("100");if((mask&8)!=0)r.leverage=n("50");
            if((mask&16)!=0)r.targetNet=n("BUY".equals(direction)?"49":"-51");
            SimpleManualOrderGenerator.Candidate c=solve(r);assertTrue(c.open.time<c.close.time);
            if(r.openPrice!=null)assertEquals(r.openPrice,c.openPrice);if(r.closePrice!=null)assertEquals(r.closePrice,c.closePrice);if(r.quantity!=null)assertEquals(r.quantity,c.calculation.get("quantity"));
            if(r.leverage!=null)assertEquals(r.leverage,c.leverage);assertEquals(direction,c.side);assertTrue(SimpleManualOrderGenerator.matches(c.calculation.get("net"),r.targetNet,r.allowNetAdjustment,r.netTolerance));
        }
    }
}
