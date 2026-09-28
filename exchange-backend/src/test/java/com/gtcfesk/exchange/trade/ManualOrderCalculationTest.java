package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManualOrderCalculationTest {
    static BigDecimal n(String s){return new BigDecimal(s);}
    static void equal(String expected,Object actual){assertEquals(0,n(expected).compareTo(new BigDecimal(actual.toString())));}
    Map<String,BigDecimal> calc(String mode,String value,String side,String balance,String close,String leverage){
        return ManualOrderCalculation.calculate(mode,n(value),side,n(balance),n("100"),n(close),n("1"),n(leverage),n("1"),n("1"),n("2"));
    }
    @Test void quantityPercentAndTargetNetAgree(){
        Map<String,BigDecimal> a=calc("QUANTITY","10","BUY","1000","110","10");equal("100",a.get("margin"));equal("20",a.get("fee"));equal("100",a.get("profit"));equal("80",a.get("net"));equal("12",a.get("percent"));
        a=calc("PERCENT","120","BUY","1000","110","10");equal("100",a.get("quantity"));equal("800",a.get("net"));
        a=calc("NET","100","BUY","1000","110","10");equal("12.50",a.get("quantity"));equal("125",a.get("margin"));equal("25",a.get("fee"));equal("15",a.get("percent"));
        equal("200",calc("PERCENT","240","BUY","1000","110","10").get("quantity"));
    }
    @Test void shortLeverageAndNegativeBalance(){
        equal("80",calc("QUANTITY","10","SELL","1000","90","10").get("net"));
        equal("80",calc("QUANTITY","10","BUY","1000","110","1000").get("net"));
        equal("1",calc("QUANTITY","10","BUY","1000","110","1000").get("margin"));
        assertNull(calc("NET","100","BUY","-50","110","10").get("percent"));
        assertThrows(BusinessException.class,()->calc("PERCENT","120","BUY","0","110","10"));
    }
    @Test void stepsNoSolutionSignsAndOverflow(){
        equal("-0.02",calc("NET","0.1","BUY","1000","110","10").get("difference"));
        assertThrows(BusinessException.class,()->calc("NET","100","BUY","1000","102","10"));
        assertThrows(BusinessException.class,()->calc("NET","-100","BUY","1000","110","10"));
        assertThrows(BusinessException.class,()->calc("QUANTITY","0.001","BUY","1000","110","10"));
        assertThrows(BusinessException.class,()->calc("QUANTITY","10000000000000000","BUY","1000","110","10"));
        assertThrows(BusinessException.class,()->calc("QUANTITY","1","BUY","1000","110","100000000"));
        assertThrows(BusinessException.class,()->calc("QUANTITY","1","BUY","1000","110","0"));
        assertThrows(NumberFormatException.class,()->n("NaN"));assertThrows(NumberFormatException.class,()->n("Infinity"));
    }
    @Test void timezoneCrossDayAndDst(){
        assertEquals(ManualOrderCalculation.minute("2026-01-01T00:00","Asia/Singapore",null),ManualOrderCalculation.minute("2025-12-31T16:00","UTC",null));
        assertThrows(BusinessException.class,()->ManualOrderCalculation.minute("2026-03-08T02:30","America/New_York",null));
        assertThrows(BusinessException.class,()->ManualOrderCalculation.minute("2025-11-02T01:30","America/New_York",null));
        assertEquals(3600000,ManualOrderCalculation.minute("2025-11-02T01:30","America/New_York","-05:00")-ManualOrderCalculation.minute("2025-11-02T01:30","America/New_York","-04:00"));
        assertThrows(BusinessException.class,()->ManualOrderCalculation.minute("2026-01-01T00:00:01","UTC",null));
    }
    @Test void exactMinuteOnlyNoZeroOrNeighbourFallback(){
        Map<String,Object> row=new HashMap<>();row.put("timestamp",1700000040L);row.put("open","123");
        Map<String,Object> response=Collections.singletonMap("data",Collections.singletonMap("kline_list",Collections.singletonList(row)));
        equal("123",ManualOrderPrices.exact(response,1700000040000L));
        assertThrows(BusinessException.class,()->ManualOrderPrices.exact(response,1699999980000L));
        row.put("open","0");assertThrows(BusinessException.class,()->ManualOrderPrices.exact(response,1700000040000L));
        row.put("open","0.006345179164260625839");equal("0.0063451791642606",ManualOrderPrices.exact(response,1700000040000L));
    }
}
