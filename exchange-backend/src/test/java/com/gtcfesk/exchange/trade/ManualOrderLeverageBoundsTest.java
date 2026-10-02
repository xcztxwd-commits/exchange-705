package com.gtcfesk.exchange.trade;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.gtcfesk.exchange.common.BusinessException;
import static org.junit.jupiter.api.Assertions.*;

class ManualOrderLeverageBoundsTest {
    static BigDecimal n(String s){return new BigDecimal(s);}
    ManualOrderGenerator.Candidate solve(String target,String max) {
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.leverage=target==null?null:n(target);
        return ManualOrderGenerator.solve(r,ManualOrderToleranceTest.candles(n("110")),ManualOrderToleranceTest.OPEN,ManualOrderToleranceTest.CLOSE,n("1000"),BigDecimal.ONE,n("2"),1,n("0.01"),n("0.01"),BigDecimal.ZERO,n(max));
    }
    @ParameterizedTest @ValueSource(strings={"0.5","0.95","0.96","5.26","5.27","10"})
    void disjointTargetIsRejected(String target){assertThrows(BusinessException.class,()->solve(target,"5"));}
    @ParameterizedTest @ValueSource(strings={"1","4.99","5"})
    void exactTargetIsAcceptedWithoutClamping(String target){
        ManualOrderGenerator.Candidate c=solve(target,"5");
        assertTrue(c.leverage.compareTo(BigDecimal.ONE)>=0);assertTrue(c.leverage.compareTo(n("5"))<=0);
        assertEquals(0,c.leverage.compareTo(n(target)));
        assertEquals(0,c.calculation.get("margin").compareTo(c.calculation.get("quantity").multiply(n("100")).divide(c.leverage,16,java.math.RoundingMode.CEILING)));
    }
    @Test void fixedLeverageCannotCrossInstrumentBoundsEvenByFivePercent(){
        assertThrows(BusinessException.class,()->solve("20","19"));
        assertEquals(0,solve("19","19").leverage.compareTo(n("19")));
        assertThrows(BusinessException.class,()->solve("20.01","19"));
        assertThrows(BusinessException.class,()->solve("1.05","1"));
        assertEquals(0,solve("1","1").leverage.compareTo(BigDecimal.ONE));
        assertThrows(BusinessException.class,()->solve("1.06","1"));
    }
    @Test void freeLeverageCanUseOneAndFractionalMaximum(){
        assertEquals(0,solve(null,"1").leverage.compareTo(BigDecimal.ONE));
        assertTrue(solve(null,"1.23").leverage.compareTo(n("1.23"))<=0);
        assertEquals(0,solve("10.4","20").leverage.compareTo(n("10.4")));
    }
    @ParameterizedTest @ValueSource(strings={"0.99","100.01","0","-1"})
    void invalidConfiguredMaximumRejected(String max){assertThrows(BusinessException.class,()->solve(null,max));}
}
