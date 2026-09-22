package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PriceControlV2Test {
    TradingSymbol plan(int intensity, boolean random) {
        TradingSymbol p=new TradingSymbol();p.setSymbol("TEST");p.setPricePrecision(4);
        p.setControlStartedAt(1700000000000L);p.setControlDurationSeconds(20);
        p.setControlStartPrice(BigDecimal.valueOf(90));p.setControlTargetPrice(BigDecimal.valueOf(110));
        p.setControlIntensity(intensity);p.setControlRandomOscillation(random);return p;
    }
    @Test void intensityChangesBothWaveShapesWithoutChangingEndpointsOrDeterminism() {
        for(boolean random:new boolean[]{false,true}) {
            TradingSymbol mild=plan(1,random),strong=plan(10,random);
            BigDecimal mildVariation=BigDecimal.ZERO,strongVariation=BigDecimal.ZERO;
            BigDecimal lastMild=mild.getControlStartPrice(),lastStrong=lastMild;
            boolean rose=false,fell=false;
            for(int second=1;second<=20;second++) {
                long time=strong.getControlStartedAt()+second*1000;
                BigDecimal a=PriceControlPath.price(mild,time,2),b=PriceControlPath.price(strong,time,2);
                mildVariation=mildVariation.add(a.subtract(lastMild).abs());
                strongVariation=strongVariation.add(b.subtract(lastStrong).abs());
                rose|=b.compareTo(lastStrong)>0;fell|=b.compareTo(lastStrong)<0;
                assertEquals(b,PriceControlPath.price(plan(10,random),time+999,2));
                assertTrue(b.signum()>0);lastMild=a;lastStrong=b;
            }
            assertTrue(strongVariation.compareTo(mildVariation)>0);
            assertTrue(rose&&fell);
            assertEquals(strong.getControlStartPrice(),PriceControlPath.price(strong,strong.getControlStartedAt(),2));
            assertEquals(strong.getControlTargetPrice(),lastStrong);
        }
    }
    @Test void originalAlgorithmRemainsLinearWhenRandomIsOffAndNewRegularWaveIsExact() {
        TradingSymbol p=plan(10,false);
        assertEquals(0,BigDecimal.valueOf(95).compareTo(PriceControlPath.price(p,p.getControlStartedAt()+5000,1)));
        assertEquals(0,new BigDecimal("102.5").compareTo(PriceControlPath.price(p,p.getControlStartedAt()+5000,2)));
        assertThrows(IllegalArgumentException.class,()->PriceControlPath.price(p,p.getControlStartedAt(),3));
    }
}
