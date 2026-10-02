package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManualOrderGeneratorTest {
    static BigDecimal n(String value){return new BigDecimal(value);}
    static final long START=1700000040000L;
    ManualOrderGenerator.Request request(){ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.targetNet=n("100");return r;}
    NavigableMap<Long,ManualOrderGenerator.Candle> candles(){
        NavigableMap<Long,ManualOrderGenerator.Candle> c=new TreeMap<>();
        for(int i=0;i<1500;i++)c.put(START+i*60000L,new ManualOrderGenerator.Candle(START+i*60000L,n("100").add(BigDecimal.valueOf(i).movePointLeft(2)),BigDecimal.ONE));
        return c;
    }
    ManualOrderGenerator.Candidate solve(ManualOrderGenerator.Request r,Long a,Long b){return ManualOrderGenerator.solve(r,candles(),a,b,n("1000"),BigDecimal.ONE,n("2"),42);}
    @Test void onlyNetGeneratesAllWithHoursPreferred(){
        ManualOrderGenerator.Candidate c=solve(request(),null,null);
        assertTrue(ManualOrderGenerator.withinTarget(c.calculation.get("net"),n("100")));
        assertTrue(c.close.time-c.open.time>=60*60000L);
        assertTrue(c.calculation.get("quantity").signum()>0);
    }
    @Test void fixedOpeningAndTarget(){ManualOrderGenerator.Candidate c=solve(request(),START,null);assertEquals(START,c.open.time);assertTrue(ManualOrderGenerator.withinTarget(c.calculation.get("net"),n("100")));}
    @Test void fixedClosingAndNegativeTarget(){ManualOrderGenerator.Request r=request();r.targetNet=n("-100");long close=START+1400*60000L;ManualOrderGenerator.Candidate c=solve(r,null,close);assertEquals(close,c.close.time);assertTrue(ManualOrderGenerator.withinTarget(c.calculation.get("net"),r.targetNet));}
    @Test void fixedQuantityDirectionAndLeverageStayFixed(){ManualOrderGenerator.Request r=request();r.quantity=n("10");r.side="BUY";r.leverage=n("12.5");ManualOrderGenerator.Candidate c=solve(r,START,null);assertEquals(0,c.calculation.get("quantity").compareTo(r.quantity));assertEquals(r.side,c.side);assertEquals(0,c.leverage.compareTo(r.leverage));}
    @Test void fixedPercentAndNetSolveLeverage(){ManualOrderGenerator.Request r=request();r.percent=n("15");ManualOrderGenerator.Candidate c=solve(r,null,null);assertTrue(c.calculation.get("percent").subtract(r.percent).abs().compareTo(n("0.01"))<=0);assertTrue(ManualOrderGenerator.withinTarget(c.calculation.get("net"),r.targetNet));}
    @Test void allocationAndNetTargetsCanBothMoveWithinFivePercent(){
        ManualOrderGenerator.Request r=request();r.percent=n("12");r.leverage=n("10");r.side="BUY";r.targetNet=n("83");
        ManualOrderGenerator.Candidate c=solve(r,START,START+1000*60000L);
        assertTrue(c.calculation.get("quantity").compareTo(n("10"))>0);
        assertTrue(c.calculation.get("net").compareTo(n("80"))>0);
        assertTrue(ManualOrderGenerator.matches(r,c.calculation,c.leverage));
        assertTrue(ManualOrderGenerator.withinTarget(c.calculation.get("net"),r.targetNet));
    }
    @Test void quantityAndNetTargetsCanBothMoveWithinFivePercent(){
        ManualOrderGenerator.Request r=request();r.quantity=n("10");r.targetNet=n("84.8");r.leverage=n("10");r.side="BUY";
        ManualOrderGenerator.Candidate c=solve(r,START,START+1000*60000L);
        assertTrue(c.calculation.get("quantity").compareTo(r.quantity)>0);
        assertTrue(c.calculation.get("net").compareTo(r.targetNet)<0);
        assertTrue(ManualOrderGenerator.withinTarget(c.calculation.get("quantity"),r.quantity));
        assertTrue(ManualOrderGenerator.withinTarget(c.calculation.get("net"),r.targetNet));
    }
    @Test void arbitraryQuantityAndPercentWithoutNet(){ManualOrderGenerator.Request r=request();r.targetNet=null;r.quantity=n("10");r.percent=n("15");ManualOrderGenerator.Candidate c=solve(r,null,null);assertEquals(0,c.calculation.get("quantity").compareTo(r.quantity));assertTrue(ManualOrderGenerator.matches(r,c.calculation,c.leverage));}
    @Test void percentOnlyCanAdjustUnpinnedLeverageAfterLotRounding(){
        ManualOrderGenerator.Request r=request();r.targetNet=null;r.percent=n("120");
        ManualOrderGenerator.Candidate c=ManualOrderGenerator.solve(r,candles(),START,null,n("10"),n("1000"),n("30"),42);
        assertTrue(ManualOrderGenerator.matches(r,c.calculation,c.leverage));
        assertEquals(0,c.calculation.get("quantity").compareTo(n("0.01")));
    }
    @Test void fivePercentIsInclusiveButNotRoundedIn(){assertTrue(ManualOrderGenerator.withinTarget(n("105"),n("100")));assertTrue(ManualOrderGenerator.withinTarget(n("-95"),n("-100")));assertFalse(ManualOrderGenerator.withinTarget(n("105.0000000000000001"),n("100")));assertFalse(ManualOrderGenerator.withinTarget(n("-94.99"),n("-100")));}
    @Test void quantityAndAllocationAlsoHaveExactFivePercentBoundaries(){ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.quantity=n("10");r.percent=n("100");Map<String,BigDecimal> c=new HashMap<>();c.put("quantity",n("10.5"));c.put("percent",n("95"));assertTrue(ManualOrderGenerator.matches(r,c,n("10")));c.put("quantity",n("10.5000000000000001"));assertFalse(ManualOrderGenerator.matches(r,c,n("10")));c.put("quantity",n("10.5"));c.put("percent",n("94.99999999"));assertFalse(ManualOrderGenerator.matches(r,c,n("10")));}
    @Test void closePriceTargetChecksRealCandleWithinFivePercent(){ManualOrderGenerator.Request r=request();r.targetNet=null;r.targetClosePrice=n("105");ManualOrderGenerator.Candidate c=solve(r,START,START+240*60000L);assertEquals(0,c.close.price.compareTo(n("102.4")));assertTrue(ManualOrderGenerator.withinTarget(c.close.price,r.targetClosePrice));r.targetClosePrice=n("100");assertTrue(ManualOrderGenerator.withinTarget(c.close.price,r.targetClosePrice));r.targetClosePrice=n("90");assertThrows(BusinessException.class,()->solve(r,START,START+240*60000L));}
    @Test void zeroTargetRequiresZero(){assertTrue(ManualOrderGenerator.withinTarget(BigDecimal.ZERO,BigDecimal.ZERO));assertFalse(ManualOrderGenerator.withinTarget(n("0.0000000000000001"),BigDecimal.ZERO));ManualOrderGenerator.Request r=request();r.targetNet=BigDecimal.ZERO;NavigableMap<Long,ManualOrderGenerator.Candle> c=new TreeMap<>();c.put(START,new ManualOrderGenerator.Candle(START,n("100"),BigDecimal.ONE));c.put(START+14400000,new ManualOrderGenerator.Candle(START+14400000,n("100"),BigDecimal.ONE));assertEquals(0,ManualOrderGenerator.solve(r,c,null,null,n("1000"),BigDecimal.ONE,BigDecimal.ZERO,1).calculation.get("net").signum());}
    @Test void impossibleTargetReportsNearestNotForged(){ManualOrderGenerator.Request r=request();r.quantity=n("1");r.side="BUY";BusinessException e=assertThrows(BusinessException.class,()->solve(r,START,START+60000L));assertTrue(e.getMessage().contains("最接近"));assertEquals(n("100"),r.targetNet);}
    @Test void fixedFewMinutesNotHardBanned(){ManualOrderGenerator.Request r=request();r.targetNet=n("-10");assertEquals(5*60000L,solve(r,START,START+5*60000L).close.time-START);assertTrue(ManualOrderGenerator.score(5,n("15"),n("10"))>ManualOrderGenerator.score(240,n("15"),n("10"))+50);}
    @Test void conflictingPinsAndMissingMinuteRejected(){ManualOrderGenerator.Request r=request();r.quantity=n("10");r.percent=n("15");r.leverage=n("100");assertThrows(BusinessException.class,()->solve(r,START,START+60000L));assertThrows(BusinessException.class,()->solve(request(),START-60000L,null));}
    @Test void negativeBalanceAndInvalidPins(){ManualOrderGenerator.Request r=request();r.percent=n("10");assertThrows(BusinessException.class,()->ManualOrderGenerator.validate(r,n("-1")));r.percent=null;r.quantity=n("1.001");assertDoesNotThrow(()->ManualOrderGenerator.validate(r,n("1000")));r.quantity=n("0.00000000000000001");assertThrows(BusinessException.class,()->ManualOrderGenerator.validate(r,n("1000")));}
    @Test void noNetConstraintCanChooseMinimumLotForSmallWallet(){ManualOrderGenerator.Request r=request();r.targetNet=null;ManualOrderGenerator.Candidate c=ManualOrderGenerator.solve(r,candles(),START,null,n("0.01"),n("1000"),n("30"),2);assertEquals(0,c.calculation.get("quantity").compareTo(n("0.01")));assertTrue(c.calculation.get("percent").compareTo(n("100"))>0);}
}
