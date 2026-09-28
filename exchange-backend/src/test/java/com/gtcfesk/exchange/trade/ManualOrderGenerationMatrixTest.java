package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** Independent formula oracle; every mask has the known solution BUY/10 lots/10x/12%/80 net. */
class ManualOrderGenerationMatrixTest {
    static final long OPEN=1700000040000L, CLOSE=OPEN+240*60000L;
    static final List<String> evidence=new ArrayList<>();
    static BigDecimal n(String s){return new BigDecimal(s);}
    static IntStream masks(){return IntStream.range(0,128);}
    static boolean pin(int mask,int bit){return (mask&(1<<bit))!=0;}
    static NavigableMap<Long,ManualOrderGenerator.Candle> candles(){
        NavigableMap<Long,ManualOrderGenerator.Candle> c=new TreeMap<>();
        c.put(OPEN,new ManualOrderGenerator.Candle(OPEN,n("100"),BigDecimal.ONE));
        c.put(CLOSE,new ManualOrderGenerator.Candle(CLOSE,n("110"),BigDecimal.ONE));return c;
    }
    static ManualOrderGenerator.Request request(int mask){
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();
        r.targetNet=pin(mask,0)?n("80"):null;r.side=pin(mask,3)?"BUY":null;
        r.leverage=pin(mask,4)?n("10"):null;r.quantity=pin(mask,5)?n("10"):null;
        r.percent=pin(mask,6)?n("12"):null;return r;
    }
    static void equal(BigDecimal expected,BigDecimal actual){assertEquals(0,expected.compareTo(actual),expected+" != "+actual);}
    static void verify(ManualOrderGenerator.Request r,ManualOrderGenerator.Candidate c,NavigableMap<Long,ManualOrderGenerator.Candle> prices){
        assertSame(prices.get(c.open.time),c.open);assertSame(prices.get(c.close.time),c.close);
        assertTrue(c.open.time<=c.close.time);BigDecimal q=c.calculation.get("quantity");
        assertTrue(q.signum()>0);equal(BigDecimal.ZERO,q.remainder(n("0.01")));
        BigDecimal margin=q.multiply(c.open.price).divide(c.leverage,16,RoundingMode.CEILING);
        BigDecimal fee=q.multiply(n("2"));
        BigDecimal gross=c.close.price.subtract(c.open.price).multiply(q).multiply("BUY".equals(c.side)?BigDecimal.ONE:BigDecimal.ONE.negate());
        BigDecimal net=gross.subtract(fee),pct=margin.add(fee).divide(n("10"),8,RoundingMode.HALF_UP);
        equal(margin,c.calculation.get("margin"));equal(fee,c.calculation.get("fee"));equal(gross,c.calculation.get("profit"));equal(net,c.calculation.get("net"));equal(pct,c.calculation.get("percent"));
        if(r.targetNet!=null)assertTrue(net.subtract(r.targetNet).abs().compareTo(r.targetNet.abs().multiply(n("0.05")))<=0);
        if(r.quantity!=null)equal(r.quantity,q);if(r.leverage!=null)equal(r.leverage,c.leverage);
        if(r.side!=null)assertEquals(r.side,c.side);if(r.percent!=null)assertTrue(pct.subtract(r.percent).abs().compareTo(n("0.01"))<=0);
    }
    @ParameterizedTest(name="mask={0}") @MethodSource("masks") void all128KnownFeasibleMasks(int mask){
        ManualOrderGenerator.Request r=request(mask);NavigableMap<Long,ManualOrderGenerator.Candle> prices=candles();
        long start=System.nanoTime();String status="FAIL",actual="";
        try {
            ManualOrderGenerator.Candidate c=ManualOrderGenerator.solve(r,prices,pin(mask,1)?OPEN:null,pin(mask,2)?CLOSE:null,n("1000"),BigDecimal.ONE,n("2"),705L);
            verify(r,c,prices);if(pin(mask,1))assertEquals(OPEN,c.open.time);if(pin(mask,2))assertEquals(CLOSE,c.close.time);
            actual=c.open.time+";"+c.close.time+";"+c.side+";"+c.leverage+";"+c.calculation;status="PASS";
        } finally {evidence.add(mask+",\""+String.format("%7s",Integer.toBinaryString(mask)).replace(' ','0')+"\",\"net/open/close/side/leverage/quantity/percent bits 0..6; known feasible 80/OPEN/CLOSE/BUY/10/10/12\",\""+actual+"\","+status+","+(System.nanoTime()-start)/1000000.0);}
    }
    @AfterAll static void save() throws Exception {
        Path dir=Paths.get(System.getProperty("manual.reportDir","target/manual-generation-matrix"));Files.createDirectories(dir);
        List<String> rows=new ArrayList<>();rows.add("mask,bits,input_and_expected,actual_independent_formula_assertions,status,milliseconds");rows.addAll(evidence);
        Files.write(dir.resolve("matrix-128.csv"),rows,StandardCharsets.UTF_8);
    }
    @Test void boundedSeedsPreserveAllConstraints(){
        Random random=new Random(7052026L);
        for(int i=0;i<256;i++){
            int mask=random.nextInt(128);ManualOrderGenerator.Request r=request(mask);NavigableMap<Long,ManualOrderGenerator.Candle> prices=candles();
            ManualOrderGenerator.Candidate c=ManualOrderGenerator.solve(r,prices,pin(mask,1)?OPEN:null,pin(mask,2)?CLOSE:null,n("1000"),BigDecimal.ONE,n("2"),random.nextLong());verify(r,c,prices);
        }
    }
    @Test void finalSelectionPrefersHoursAcrossSeeds(){
        NavigableMap<Long,ManualOrderGenerator.Candle> prices=candles();prices.put(OPEN+5*60000,new ManualOrderGenerator.Candle(OPEN+5*60000,n("110"),BigDecimal.ONE));
        for(int seed=0;seed<64;seed++){
            ManualOrderGenerator.Request r=request(1|8|16|32);ManualOrderGenerator.Candidate c=ManualOrderGenerator.solve(r,prices,OPEN,null,n("1000"),BigDecimal.ONE,n("2"),seed);
            assertEquals(240,(c.close.time-c.open.time)/60000);verify(r,c,prices);
        }
    }
    @Test void randomizedKnownSolutionsAcrossSignsPricesAndSizes(){
        Random random=new Random(705928L);
        for(int i=0;i<128;i++){
            NavigableMap<Long,ManualOrderGenerator.Candle> prices=new TreeMap<>();BigDecimal opening=BigDecimal.valueOf(10+random.nextInt(990)),delta=BigDecimal.valueOf(random.nextInt(41)-20);
            if(opening.add(delta).signum()<=0)delta=BigDecimal.ONE;
            prices.put(OPEN,new ManualOrderGenerator.Candle(OPEN,opening,BigDecimal.ONE));prices.put(CLOSE,new ManualOrderGenerator.Candle(CLOSE,opening.add(delta),BigDecimal.ONE));
            ManualOrderGenerator.Request r=request(127);r.quantity=BigDecimal.valueOf(1+random.nextInt(2000),2);r.side=random.nextBoolean()?"BUY":"SELL";
            r.targetNet=delta.multiply("BUY".equals(r.side)?BigDecimal.ONE:BigDecimal.ONE.negate()).subtract(n("2")).multiply(r.quantity);
            r.percent=r.quantity.multiply(opening.divide(n("10")).add(n("2"))).divide(n("10"));
            verify(r,ManualOrderGenerator.solve(r,prices,OPEN,CLOSE,n("1000"),BigDecimal.ONE,n("2"),random.nextLong()),prices);
        }
    }
    @Test void londonDstAndNumericEdges(){
        assertThrows(BusinessException.class,()->ManualOrderCalculation.minute("2026-03-29T01:30","Europe/London",null));
        assertThrows(BusinessException.class,()->ManualOrderCalculation.minute("2026-10-25T01:30","Europe/London",null));
        assertEquals(3600000,ManualOrderCalculation.minute("2026-10-25T01:30","Europe/London","Z")-ManualOrderCalculation.minute("2026-10-25T01:30","Europe/London","+01:00"));
        for(String value:new String[]{"0.0000000000000001","9999999999999999"}){ManualOrderGenerator.Request r=request(127);r.targetNet=n(value);assertThrows(BusinessException.class,()->ManualOrderGenerator.solve(r,candles(),OPEN,CLOSE,n("1000"),BigDecimal.ONE,n("2"),1));}
        for(String balance:new String[]{"0","-100"}){ManualOrderGenerator.Request r=request(1|8|16|32);assertNull(ManualOrderGenerator.solve(r,candles(),OPEN,CLOSE,n(balance),BigDecimal.ONE,n("2"),1).calculation.get("percent"));r.percent=n("120");assertThrows(BusinessException.class,()->ManualOrderGenerator.validate(r,n(balance)));}
        for(String pct:new String[]{"99.99","100","100.01","120"}){ManualOrderGenerator.Request r=request(16|64);r.percent=n(pct);ManualOrderGenerator.Candidate c=ManualOrderGenerator.solve(r,candles(),OPEN,CLOSE,n("1000"),BigDecimal.ONE,n("2"),1);verify(r,c,candlesFor(c));}
    }
    private static NavigableMap<Long,ManualOrderGenerator.Candle> candlesFor(ManualOrderGenerator.Candidate c){NavigableMap<Long,ManualOrderGenerator.Candle> map=new TreeMap<>();map.put(c.open.time,c.open);map.put(c.close.time,c.close);return map;}
    @Test void sameMinuteOnlyFeasibleWithOneEndpoint(){
        NavigableMap<Long,ManualOrderGenerator.Candle> prices=new TreeMap<>();prices.put(OPEN,candles().get(OPEN));
        ManualOrderGenerator.Request r=request(8|16|32);r.targetNet=n("-20");
        for(int mode=0;mode<3;mode++){
            ManualOrderGenerator.Candidate c=ManualOrderGenerator.solve(r,prices,mode==1?OPEN:null,mode==2?OPEN:null,n("1000"),BigDecimal.ONE,n("2"),42);
            verify(r,c,prices);assertEquals(OPEN,c.open.time);assertEquals(OPEN,c.close.time);
        }
    }
    @Test void conflictsMissingDataAndInvalidPrecision(){
        ManualOrderGenerator.Request r=request(127);r.percent=n("15");assertThrows(BusinessException.class,()->ManualOrderGenerator.solve(r,candles(),OPEN,CLOSE,n("1000"),BigDecimal.ONE,n("2"),1));
        for(String invalid:new String[]{"0","-1","0.001","1E+30"}){r.quantity=n(invalid);assertThrows(BusinessException.class,()->ManualOrderGenerator.validate(r,n("1000")));}
        assertThrows(BusinessException.class,()->ManualOrderGenerator.solve(request(0),new TreeMap<>(),null,null,n("1000"),BigDecimal.ONE,n("2"),1));
    }
}
