package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.math.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ManualOrderToleranceTest {
    static BigDecimal n(String v) { return new BigDecimal(v); }
    static final long OPEN=1700000040000L, CLOSE=OPEN+240*60000L;
    static NavigableMap<Long,ManualOrderGenerator.Candle> candles(BigDecimal close) {
        NavigableMap<Long,ManualOrderGenerator.Candle> out=new TreeMap<>();
        out.put(OPEN,new ManualOrderGenerator.Candle(OPEN,n("100"),BigDecimal.ONE));
        out.put(CLOSE,new ManualOrderGenerator.Candle(CLOSE,close,BigDecimal.ONE));
        return out;
    }
    static void within(BigDecimal actual,BigDecimal target) {
        assertTrue(actual.subtract(target).abs().compareTo(target.abs().multiply(n("0.05")))<=0,actual+" outside "+target);
    }
    @ParameterizedTest @ValueSource(strings={"9.5","10.5"})
    void leverageBoundaryIncluded(String value) {
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.leverage=n("10");
        assertTrue(ManualOrderGenerator.matches(r,Collections.emptyMap(),n(value)));
        assertFalse(ManualOrderGenerator.matches(r,Collections.emptyMap(),n(value).add(n(value).compareTo(n("10"))<0?n("-0.0000000001"):n("0.0000000001"))));
    }
    @ParameterizedTest @ValueSource(strings={"9.55","10.45"})
    void adjustableLeverageRescuesIntegerQuantity(String witness) {
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();
        r.side="BUY";r.leverage=n("10");r.quantity=BigDecimal.ONE;r.targetNet=n("8");
        // Put the exact-leverage allocation just outside the permitted band.
        BigDecimal percent=n("100").divide(n(witness),16,RoundingMode.HALF_UP).add(n("2")).divide(n("10"));
        r.percent=percent.multiply(n(witness).compareTo(n("10"))<0?n("1.02"):n("0.98")).setScale(16,RoundingMode.HALF_UP);
        ManualOrderGenerator.Candidate c=ManualOrderGenerator.solve(r,candles(n("110")),OPEN,CLOSE,n("1000"),BigDecimal.ONE,n("2"),1,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO);
        assertNotEquals(0,c.leverage.compareTo(r.leverage));within(c.leverage,r.leverage);within(c.calculation.get("percent"),r.percent);
        assertEquals(n("10"),r.leverage);assertEquals("BUY",c.side);assertEquals(OPEN,c.open.time);assertEquals(CLOSE,c.close.time);
    }
    static IntStream cases() { return IntStream.range(0,512); }
    @ParameterizedTest(name="feasible perturbed targets {0}") @MethodSource("cases")
    void knownFeasiblePerturbedTargets(int index) {
        Random random=new Random(20260929L+index);
        BigDecimal q=BigDecimal.valueOf(1+random.nextInt(30)), leverage=BigDecimal.valueOf(5+random.nextInt(46));
        BigDecimal fee=index%3==0?BigDecimal.ZERO:n("2"), close=index%3==0?n("100"):n("110");
        String side=index%2==0?"BUY":"SELL";
        BigDecimal net=close.subtract(n("100")).multiply("BUY".equals(side)?BigDecimal.ONE:BigDecimal.ONE.negate()).subtract(fee).multiply(q);
        BigDecimal percent=q.multiply(n("100")).divide(leverage,16,RoundingMode.CEILING).add(q.multiply(fee)).divide(n("10"),8,RoundingMode.HALF_UP);
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.side=side;
        r.quantity=perturb(q,random);r.leverage=perturb(leverage,random).setScale(2,RoundingMode.HALF_UP);
        r.targetNet=perturb(net,random);r.percent=perturb(percent,random);r.targetClosePrice=perturb(close,random);
        NavigableMap<Long,ManualOrderGenerator.Candle> prices=candles(close);
        ManualOrderGenerator.Candidate c=ManualOrderGenerator.solve(r,prices,OPEN,CLOSE,n("1000"),BigDecimal.ONE,fee,index,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO);
        within(c.leverage,r.leverage);within(c.calculation.get("quantity"),r.quantity);within(c.calculation.get("net"),r.targetNet);within(c.calculation.get("percent"),r.percent);within(c.close.price,r.targetClosePrice);
        assertSame(prices.get(OPEN),c.open);assertSame(prices.get(CLOSE),c.close);
        BigDecimal actualQ=c.calculation.get("quantity");assertEquals(0,actualQ.remainder(BigDecimal.ONE).signum());
        BigDecimal expectedNet=close.subtract(n("100")).multiply("BUY".equals(side)?BigDecimal.ONE:BigDecimal.ONE.negate()).subtract(fee).multiply(actualQ);
        assertEquals(0,expectedNet.compareTo(c.calculation.get("net")));
    }
    static BigDecimal perturb(BigDecimal v,Random random) {
        return v.multiply(BigDecimal.valueOf(960+random.nextInt(81)).movePointLeft(3)).setScale(16,RoundingMode.HALF_UP);
    }
    @Test void instrumentMinimumAndNotionalAreSearchCandidates() {
        for(BigDecimal minimum:Arrays.asList(n("5"),n("7"))) {
            ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.side="BUY";r.leverage=n("10");
            ManualOrderGenerator.Candidate c=ManualOrderGenerator.solve(r,candles(n("110")),OPEN,CLOSE,n("1"),BigDecimal.ONE,n("2"),1,n("1"),minimum,n("600"));
            assertTrue(c.calculation.get("quantity").compareTo(minimum)>=0);
            assertTrue(c.calculation.get("quantity").multiply(n("100")).compareTo(n("600"))>=0);
        }
    }
    @Test void infeasibleTargetsAreNotSilentlyRelaxed() {
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.leverage=n("10");r.quantity=n("1");r.percent=n("100");
        assertThrows(BusinessException.class,()->ManualOrderGenerator.solve(r,candles(n("110")),OPEN,CLOSE,n("1000"),BigDecimal.ONE,n("2"),1,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO));
        assertEquals(n("10"),r.leverage);assertEquals(n("100"),r.percent);
    }
}
