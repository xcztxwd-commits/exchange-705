package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every generated request has an independently calculated, legal witness. */
class ManualOrderFeasibilityTest {
    static BigDecimal n(String value){return new BigDecimal(value);}
    static final long OPEN=1700000040000L,CLOSE=OPEN+240*60000L;

    @Test void all128InputMasksAcrossMarketConditionsHaveAFeasibleResult() {
        Random random=new Random(7050928L);
        for(int repetition=0;repetition<4;repetition++)for(int mask=0;mask<128;mask++) {
            BigDecimal p0=BigDecimal.valueOf(10+random.nextInt(990));
            BigDecimal delta=BigDecimal.valueOf(random.nextInt(41)-20);
            if(p0.add(delta).signum()<=0)delta=BigDecimal.ONE;
            BigDecimal p1=p0.add(delta),lot=BigDecimal.valueOf(1+random.nextInt(1000));
            BigDecimal rate=random.nextBoolean()?BigDecimal.ONE:n("0.0063");
            BigDecimal marginRate=p0.multiply(rate),fee=BigDecimal.valueOf(random.nextInt(21));
            BigDecimal available=BigDecimal.valueOf(1000+random.nextInt(9000));
            BigDecimal quantity=BigDecimal.valueOf(1+random.nextInt(2000),2);
            BigDecimal leverage=n(new String[]{"5","10","20","50","100"}[random.nextInt(5)]);
            String side=random.nextBoolean()?"BUY":"SELL";
            Map<String,BigDecimal> witness=ManualOrderCalculation.calculate("QUANTITY",quantity,side,available,p0,p1,lot,leverage,rate,rate,fee,marginRate);
            ManualOrderGenerator.Request request=new ManualOrderGenerator.Request();
            if((mask&1)!=0)request.side=side;
            if((mask&2)!=0)request.leverage=leverage;
            if((mask&4)!=0)request.quantity=quantity.multiply((mask&64)==0?n("0.97"):n("1.03"));
            if((mask&8)!=0)request.percent=witness.get("percent").multiply((mask&64)==0?n("1.03"):n("0.97"));
            if((mask&16)!=0)request.targetNet=witness.get("net").multiply(n("1.03")).setScale(16,RoundingMode.HALF_UP);
            if((mask&32)!=0)request.targetClosePrice=p1.multiply(n("0.97"));
            NavigableMap<Long,ManualOrderGenerator.Candle> candles=new TreeMap<>();
            candles.put(OPEN,new ManualOrderGenerator.Candle(OPEN,p0,rate,marginRate));
            candles.put(CLOSE,new ManualOrderGenerator.Candle(CLOSE,p1,rate,p1.multiply(rate)));
            try {
                ManualOrderGenerator.Candidate actual=ManualOrderGenerator.solve(request,candles,(mask&64)==0?OPEN:null,CLOSE,available,lot,fee,random.nextLong());
                assertEquals(CLOSE,actual.close.time);
                if((mask&64)==0)assertEquals(OPEN,actual.open.time);
                if(request.side!=null)assertEquals(side,actual.side);
                if(request.leverage!=null)assertTrue(ManualOrderGenerator.withinTarget(actual.leverage,request.leverage));
                assertTrue(ManualOrderGenerator.matches(request,actual.calculation,actual.leverage));
                assertTrue(ManualOrderGenerator.withinTarget(actual.calculation.get("net"),request.targetNet));
                assertTrue(ManualOrderGenerator.withinTarget(actual.close.price,request.targetClosePrice));
            } catch(BusinessException failure) {
                fail("Known-feasible mask="+mask+" repetition="+repetition+" witness="+witness+" error="+failure.getMessage(),failure);
            }
        }
    }

    @Test void feeFloorInsideFivePercentMustNotBeRejected() {
        NavigableMap<Long,ManualOrderGenerator.Candle> candles=new TreeMap<>();
        candles.put(OPEN,new ManualOrderGenerator.Candle(OPEN,n("100"),BigDecimal.ONE));
        candles.put(CLOSE,new ManualOrderGenerator.Candle(CLOSE,n("100"),BigDecimal.ONE));
        ManualOrderGenerator.Request request=new ManualOrderGenerator.Request();
        request.side="BUY";request.quantity=n("0.01");request.percent=n("9.9");request.targetNet=n("-100");
        Map<String,BigDecimal> witness=ManualOrderCalculation.calculate("QUANTITY",n("0.01"),"BUY",n("1000"),n("100"),n("100"),BigDecimal.ONE,n("100"),BigDecimal.ONE,BigDecimal.ONE,n("10000"));
        assertTrue(ManualOrderGenerator.withinTarget(witness.get("percent"),request.percent));
        ManualOrderGenerator.Candidate actual=ManualOrderGenerator.solve(request,candles,OPEN,CLOSE,n("1000"),BigDecimal.ONE,n("10000"),1);
        assertTrue(ManualOrderGenerator.matches(request,actual.calculation,actual.leverage));
        assertTrue(ManualOrderGenerator.withinTarget(actual.calculation.get("net"),request.targetNet));
        request.percent=n("9");
        assertThrows(BusinessException.class,()->ManualOrderGenerator.solve(request,candles,OPEN,CLOSE,n("1000"),BigDecimal.ONE,n("10000"),1));
    }

    @Test void feeFloorCanRequireNonstandardLeverage() {
        NavigableMap<Long,ManualOrderGenerator.Candle> candles=new TreeMap<>();
        candles.put(OPEN,new ManualOrderGenerator.Candle(OPEN,n("100000"),BigDecimal.ONE));
        candles.put(CLOSE,new ManualOrderGenerator.Candle(CLOSE,n("100000"),BigDecimal.ONE));
        ManualOrderGenerator.Request request=new ManualOrderGenerator.Request();
        request.side="BUY";request.quantity=n("0.01");request.percent=n("9.5");request.targetNet=n("-98");
        BigDecimal available=n("1000"),fee=n("9800");
        // The former witness required 600x: precision-valid, but outside the 1..100 trading range.
        assertThrows(BusinessException.class,()->ManualOrderGenerator.solve(request,candles,OPEN,CLOSE,available,BigDecimal.ONE,fee,2));
    }

    @Test void toleranceBoundaryKeepsLeverageInsideSupportedPrecision() {
        NavigableMap<Long,ManualOrderGenerator.Candle> candles=new TreeMap<>();
        candles.put(OPEN,new ManualOrderGenerator.Candle(OPEN,n("1000000000000"),BigDecimal.ONE));
        candles.put(CLOSE,new ManualOrderGenerator.Candle(CLOSE,n("1000000000000"),BigDecimal.ONE));
        ManualOrderGenerator.Request request=new ManualOrderGenerator.Request();
        request.quantity=n("0.01");request.percent=n("10");
        assertThrows(BusinessException.class,()->ManualOrderGenerator.solve(request,candles,OPEN,CLOSE,n("1000"),BigDecimal.ONE,BigDecimal.ZERO,3));
    }
}
