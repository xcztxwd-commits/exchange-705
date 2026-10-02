package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.*;
import java.math.*;
import java.util.*;
import java.util.stream.Stream;
import static com.gtcfesk.exchange.trade.FeeCalculationAuditTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(CalculationTenantExtension.class)
class FxStandardContractTest {
    static Fixture fx(String name,String base,String quote,String price,String quoteRate) {
        Fixture f=new Fixture(name,base,quote,price,quoteRate);
        f.symbol.setSourceCategory("Forex"); FxContractRules.defaults(f.symbol);
        when(f.quotes.fxMarginRate(eq(base),eq(quote),any(BigDecimal.class))).thenAnswer(call ->
            "USD".equals(base)?BigDecimal.ONE:"USD".equals(quote)?call.getArgument(2):d("1.1"));
        return f;
    }
    @TestFactory Stream<DynamicTest> fxMarginAndCommissionMatrix() {
        List<DynamicTest> tests=new ArrayList<>();
        String[][] pairs={{"JPY=X","USD","JPY","157.2","0.0063","1"},
            {"CAD=X","USD","CAD","1.4","0.71","1"},{"HKD=X","USD","HKD","7.8","0.128","1"},
            {"EURUSD=X","EUR","USD","1.1","1","1.1"},{"GBPUSD=X","GBP","USD","1.25","1","1.25"},
            {"EURJPY=X","EUR","JPY","170","0.0063","1.1"}};
        for(String[] p:pairs)for(String lots:Arrays.asList("0.01","0.1","1","2.37"))for(String leverage:Arrays.asList("1","10","50","100"))for(String side:Arrays.asList("BUY","SELL"))
            tests.add(DynamicTest.dynamicTest(p[0]+"/"+lots+"/"+leverage+"/"+side,()->{
                Fixture f=fx(p[0],p[1],p[2],p[3],p[4]); ContractOrder order=f.open(lots,leverage,side);
                same(d(lots).multiply(d("100000")).multiply(d(p[5])).divide(d(leverage)),order.getMargin());
                same(d(lots).multiply(d("3.50")),order.getOpenCommission()); same(order.getOpenCommission(),order.getCloseCommission());
                same(d(lots).multiply(d("7")),order.getFee()); same(d("3.5"),f.symbol.getCommissionPerLotPerSide());
                same(f.initial.subtract(order.getMargin()).subtract(order.getFee()),f.account.getAvailable());
                same(order.getMargin().add(order.getFee()),f.account.getFrozen());
                f.service.closeOrder(1L,1L,null);same(f.initial.subtract(order.getFee()),f.account.getAvailable());
                same(BigDecimal.ZERO,f.account.getFrozen()); assertThrows(BusinessException.class,()->f.service.closeOrder(1L,1L,null));
            }));
        return tests.stream();
    }
    @Test void boundsAndCancellation() {
        Fixture f=fx("JPY=X","USD","JPY","157.2","0.0063");
        for(String q:Arrays.asList("0.001","0.015","0","-1"))assertThrows(BusinessException.class,()->f.open(q,"100","BUY"));
        f.service.createOrder(1L,f.request("1","100","BUY","LIMIT"));
        f.service.cancelOrder(1L,1L);same(f.initial,f.account.getAvailable());same(BigDecimal.ZERO,f.account.getFrozen());
        f.symbol.setLotSize(d("1000"));assertThrows(BusinessException.class,()->f.open("1","100","BUY"));
    }
    @Test void realMarginResolverUsesBaseAndRejectsMissingCrossRate() {
        ForexQuoteMarketService quotes=new ForexQuoteMarketService();
        try {
            same(BigDecimal.ONE,quotes.fxMarginRate("USD","JPY",d("157.2")));
            same(d("1.1"),quotes.fxMarginRate("EUR","USD",d("1.1")));
            assertThrows(BusinessException.class,()->quotes.fxMarginRate("EUR","JPY",d("170")));
        } finally {quotes.stop();}
    }
    @Test void snapshotsSurviveSymbolChangesAndManualCalculationUsesBaseMargin() {
        Fixture f=fx("JPY=X","USD","JPY","157.2","0.0063");ContractOrder order=f.open("1","100","BUY");
        f.symbol.setLotSize(d("1000"));f.symbol.setFeeMultiplier(d("30"));
        f.service.closeOrder(1L,1L,null);same(d("1000"),order.getMargin());same(d("7"),order.getFee());same(d("100000"),order.getLotSize());
        Map<String,BigDecimal> manual=ManualOrderCalculation.calculate("QUANTITY",d("1"),"BUY",d("10000"),d("157.2"),d("157.2"),d("100000"),d("100"),d("0.0063"),d("0.0063"),d("7"),BigDecimal.ONE);
        same(d("1000"),manual.get("margin"));same(d("-7"),manual.get("net"));
    }

    @Test void freshCrossRatesSupportDirectInverseAndRejectStaleQuotes() {
        ForexQuoteMarketService quotes=spy(new ForexQuoteMarketService());
        try {
            Map<String,Object> raw=new HashMap<>();raw.put("price",1.1);raw.put("timestamp",System.currentTimeMillis());raw.put("fetchedAt",System.currentTimeMillis());
            doReturn(raw).when(quotes).getPrice("EURUSD=X","Forex");
            same(d("1.1"),quotes.fxMarginRate("EUR","JPY",d("170")));
            Map<String,Object> inverse=new HashMap<>(raw);inverse.put("price",1.25);
            doReturn(Collections.emptyMap()).when(quotes).getPrice("CADUSD=X","Forex");
            doReturn(inverse).when(quotes).getPrice("CAD=X","Forex");
            same(d("0.8"),quotes.fxMarginRate("CAD","JPY",d("120")));
            raw.put("timestamp",System.currentTimeMillis()-61000);
            assertThrows(BusinessException.class,()->quotes.fxMarginRate("EUR","JPY",d("170")));
            inverse.put("sourceAvailable",false);
            assertThrows(BusinessException.class,()->quotes.fxMarginRate("CAD","JPY",d("120")));
        } finally {quotes.stop();}
    }
}
