package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest;
import org.junit.jupiter.api.*;
import java.math.*;
import java.util.*;
import java.util.stream.Stream;
import static com.gtcfesk.exchange.trade.FeeCalculationAuditTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** All 14 configured symbols; synthetic prices, real order service, no live account writes. */
@org.junit.jupiter.api.extension.ExtendWith(CalculationTenantExtension.class)
class AllInstrumentCalculationTest {
    static final String[][] SYMBOLS={
        {"JPY=X","Forex","USD","JPY","157.2"},{"CAD=X","Forex","USD","CAD","1.4"},
        {"HKD=X","Forex","USD","HKD","7.8"},{"EURUSD=X","Forex","EUR","USD","1.1"},
        {"GBPUSD=X","Forex","GBP","USD","1.25"},
        {"AAPL","US","AAPL","USD","200"},{"ADBE","US","ADBE","USD","300"},
        {"AMD","US","AMD","USD","150"},{"AMZN","US","AMZN","USD","180"},
        {"BABA","US","BABA","USD","100"},{"GOOG","US","GOOG","USD","170"},
        {"BTCUSDT","Crypto","BTC","USDT","80000"},{"ETHUSDT","Crypto","ETH","USDT","2500"},
        {"SOLUSDT","Crypto","SOL","USDT","120"}};
    static BigDecimal rate(String[] s,BigDecimal price) {
        return "Forex".equals(s[1]) && "USD".equals(s[2])?BigDecimal.ONE.divide(price,24,RoundingMode.HALF_UP):BigDecimal.ONE;
    }
    static Fixture fixture(String[] s) {
        String conversion=rate(s,d(s[4])).toPlainString();
        Fixture f="Forex".equals(s[1])?FxStandardContractTest.fx(s[0],s[2],s[3],s[4],conversion):new Fixture(s[0],s[2],s[3],s[4],conversion);
        f.symbol.setSourceCategory(s[1]);return f;
    }
    @TestFactory Stream<DynamicTest> everySymbolMarginFeesPnlAndDefault100() {
        List<DynamicTest> tests=new ArrayList<>();
        for(String[] s:SYMBOLS)for(String lots:Arrays.asList("0.01","1","2.37"))for(String lev:Arrays.asList("DEFAULT","50","100"))for(String side:Arrays.asList("BUY","SELL")) {
            tests.add(DynamicTest.dynamicTest(s[0]+"/"+lots+"/"+lev+"/"+side,()->{
                Fixture f=fixture(s);boolean fx="Forex".equals(s[1]);
                CreateContractOrderRequest request=f.request(lots,"100",side,"MARKET");request.setLeverage("DEFAULT".equals(lev)?null:d(lev));
                ContractOrder o=f.service.createOrder(1L,request);
                BigDecimal leverage="DEFAULT".equals(lev)?d("100"):d(lev),size=fx?d("100000"):d("1000");
                BigDecimal units=d(lots).multiply(size),p0=d(s[4]),p1=p0.multiply(d("1.01"));
                BigDecimal marginRate=fx&&"USD".equals(s[2])?BigDecimal.ONE:p0;
                same(leverage,o.getLeverage());same(units.multiply(marginRate).divide(leverage,16,RoundingMode.CEILING),o.getMargin());
                same(d(lots).multiply(fx?d("7"):d("30")),o.getFee());
                when(f.quotes.freshPrice(s[0])).thenReturn(p1);
                when(f.quotes.requireContractConversionRate(s[3],"yahoo")).thenReturn(rate(s,p1));
                BigDecimal gross=p1.subtract(p0).multiply(units).multiply(rate(s,p1)).multiply("BUY".equals(side)?BigDecimal.ONE:BigDecimal.ONE.negate()).setScale(16,RoundingMode.HALF_UP);
                f.service.closeOrder(1L,1L,null);same(gross,o.getProfit());same(f.initial.add(gross).subtract(o.getFee()),f.account.getAvailable());same(BigDecimal.ZERO,f.account.getFrozen());
            }));
        }
        return tests.stream();
    }
    @TestFactory Stream<DynamicTest> everySymbolRejectsSubStepLotsBeforeFundsChange() {
        return Arrays.stream(SYMBOLS).map(s->DynamicTest.dynamicTest(s[0],()->{
            Fixture f=fixture(s);
            for(String q:Arrays.asList("0.001","0.015","-1","0"))assertThrows(BusinessException.class,()->f.open(q,"100","BUY"));
            same(f.initial,f.account.getAvailable());same(BigDecimal.ZERO,f.account.getFrozen());verify(f.orders,never()).save(any());
            same(d("0.01"),f.symbol.getContractMinLot());same(d("0.01"),f.symbol.getContractLotStep());
        }));
    }
}
