package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.market.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline legacy-model characterization. Standard FX policy is tested in FxStandardContractTest. */
class FeeCalculationAuditTest {
    static BigDecimal d(String value) { return new BigDecimal(value); }
    static void same(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected=" + expected + ", actual=" + actual);
    }
    static final class Fixture {
        final ContractOrderRepository orders = mock(ContractOrderRepository.class);
        final AssetAccountRepository accounts = mock(AssetAccountRepository.class);
        final TradingSymbolRepository symbols = mock(TradingSymbolRepository.class);
        final ForexQuoteMarketService quotes = mock(ForexQuoteMarketService.class);
        final MarketCategoryService categories = mock(MarketCategoryService.class);
        final AssetAccount account = new AssetAccount();
        final TradingSymbol symbol = new TradingSymbol();
        final BigDecimal initial = d("1000000000");
        final ContractOrderService service;
        Fixture(String name, String base, String quote, String price, String rate) {
            symbol.setSymbol(name); symbol.setBaseCurrency(base); symbol.setQuoteCurrency(quote);
            symbol.setCategory("Forex"); symbol.setMarketSource("yahoo"); symbol.setSourceCategory("US");
            symbol.setMaxLeverage(d("100")); symbol.setVolumePrecision(2); symbol.setMinTradeAmount(d("0.01"));
            account.setUserId(1L); account.setCoin("CONTRACT"); account.setAvailable(initial); account.setFrozen(BigDecimal.ZERO);
            when(categories.leverageEnabled("Forex")).thenReturn(true);
            when(symbols.findBySymbol(name)).thenReturn(Optional.of(symbol));
            when(accounts.findByUserIdAndCoin(1L, "CONTRACT")).thenReturn(Optional.of(account));
            when(quotes.freshPrice(name)).thenReturn(d(price));
            when(quotes.requireContractConversionRate(quote, "yahoo")).thenReturn(d(rate));
            when(orders.save(any(ContractOrder.class))).thenAnswer(call -> {
                ContractOrder order = call.getArgument(0); order.setId(1L);
                when(orders.findById(1L)).thenReturn(Optional.of(order)); return order;
            });
            service = new ContractOrderService(mock(com.gtcfesk.exchange.user.KycIdentityService.class), orders, accounts, symbols, quotes,
                    mock(PlatformTransactionManager.class), categories);
        }
        CreateContractOrderRequest request(String quantity, String leverage, String side, String type) {
            CreateContractOrderRequest request = new CreateContractOrderRequest();
            request.setSymbol(symbol.getSymbol()); request.setQuantity(d(quantity)); request.setLeverage(d(leverage));
            request.setSide(side); request.setType(type); request.setPrice(quotes.freshPrice(symbol.getSymbol()));
            return request;
        }
        ContractOrder open(String quantity, String leverage, String side) {
            return service.createOrder(1L, request(quantity, leverage, side, "MARKET"));
        }
    }

    @TestFactory Stream<DynamicTest> legacyContractLotsLeverageAndBothSides() {
        String[][] pairs = {{"EURUSD=X","EUR","USD","1.1","1"}, {"GBPUSD=X","GBP","USD","1.25","1"},
                {"JPY=X","USD","JPY","150","0.006666666666666666"},
                {"CAD=X","USD","CAD","1.25","0.8"}, {"HKD=X","USD","HKD","8","0.125"}};
        List<DynamicTest> checks = new ArrayList<>();
        for (String[] pair : pairs) for (String quantity : Arrays.asList("0.01","0.1","1","2.37"))
            for (String leverage : Arrays.asList("1","10","50","100")) for (String side : Arrays.asList("BUY","SELL")) {
                checks.add(DynamicTest.dynamicTest(String.join("/",pair[0],quantity,leverage,side), () -> {
                    Fixture f = new Fixture(pair[0],pair[1],pair[2],pair[3],pair[4]);
                    ContractOrder order = f.open(quantity,leverage,side);
                    BigDecimal fee = d(quantity).multiply(d("30"));
                    BigDecimal margin = d(quantity).multiply(d("1000")).multiply(d(pair[3])).multiply(d(pair[4]))
                            .divide(d(leverage),16,RoundingMode.CEILING);
                    same(fee,order.getFee()); same(margin,order.getMargin());
                    same(f.initial.subtract(margin).subtract(fee),f.account.getAvailable());
                    same(margin.add(fee),f.account.getFrozen());
                    BigDecimal close = d(pair[3]).add(d("0.001"));
                    when(f.quotes.freshPrice(pair[0])).thenReturn(close);
                    BigDecimal gross = d("0.001").multiply(d(quantity)).multiply(d("1000")).multiply(d(pair[4]))
                            .multiply("BUY".equals(side)?BigDecimal.ONE:BigDecimal.ONE.negate()).setScale(16,RoundingMode.HALF_UP);
                    f.service.closeOrder(1L,1L,null);
                    same(gross,order.getProfit()); same(f.initial.add(gross).subtract(fee),f.account.getAvailable());
                    same(BigDecimal.ZERO,f.account.getFrozen());
                    assertThrows(BusinessException.class,()->f.service.closeOrder(1L,1L,null));
                }));
            }
        return checks.stream();
    }

    @Test void cancellationRefundsReservedFeeAndSnapshotIsNotRepriced() {
        Fixture f = new Fixture("EURUSD=X","EUR","USD","1.1","1");
        f.service.createOrder(1L,f.request("1","100","BUY","LIMIT"));
        f.service.cancelOrder(1L,1L); same(f.initial,f.account.getAvailable()); same(BigDecimal.ZERO,f.account.getFrozen());
        assertThrows(BusinessException.class,()->f.service.cancelOrder(1L,1L));
        ContractOrder order = f.open("1","100","BUY");
        f.symbol.setFeeMultiplier(d("99")); f.symbol.setLotSize(d("100000"));
        f.service.closeOrder(1L,1L,null);
        same(d("30"),order.getFee()); same(d("1000"),order.getLotSize()); same(f.initial.subtract(d("30")),f.account.getAvailable());
    }

    @Test void nonUsdProfitIsConvertedAtSettlementRate() {
        Fixture f = new Fixture("JPY=X","USD","JPY","150","0.006666666666666666");
        ContractOrder order = f.open("1","100","BUY");
        when(f.quotes.freshPrice("JPY=X")).thenReturn(d("151"));
        when(f.quotes.requireContractConversionRate("JPY","yahoo")).thenReturn(d("0.006622516556291391"));
        f.service.closeOrder(1L,1L,null);
        same(d("6.622516556291391"),order.getProfit());
        same(f.initial.add(order.getProfit()).subtract(d("30")),f.account.getAvailable());
    }

    @Test void missingConversionFailsBeforeAnyBalanceChange() {
        Fixture f = new Fixture("JPY=X","USD","JPY","150","0.006666666666666666");
        when(f.quotes.requireContractConversionRate("JPY","yahoo")).thenThrow(new BusinessException("missing conversion"));
        assertThrows(BusinessException.class,()->f.open("1","100","BUY"));
        same(f.initial,f.account.getAvailable()); same(BigDecimal.ZERO,f.account.getFrozen()); verify(f.orders,never()).save(any());
    }

    @Test void leverageBoundariesAndNegativeFeesAreRejected() {
        Fixture f = new Fixture("EURUSD=X","EUR","USD","1.1","1");
        for (String leverage : Arrays.asList("0","101","1.5"))
            assertThrows(BusinessException.class,()->f.open("1",leverage,"BUY"));
        f.symbol.setFeeMultiplier(d("-1")); assertThrows(BusinessException.class,()->f.open("1","100","BUY"));
        f.symbol.setFeeMultiplier(BigDecimal.ZERO); ContractOrder free = f.open("1","100","BUY");
        same(BigDecimal.ZERO,free.getFee()); f.service.closeOrder(1L,1L,null); same(f.initial,f.account.getAvailable());
    }

    @Test void rejectsSubStepLotsAndPreservesLegacyFeeModel() {
        Fixture f = new Fixture("EURUSD=X","EUR","USD","1.1","1");
        assertThrows(BusinessException.class,()->f.open("0.001","100","BUY"));
        same(f.initial,f.account.getAvailable()); // Previously accepted below the advertised lot step.
        Map<String,BigDecimal> manual = ManualOrderCalculation.calculate("QUANTITY",d("1"),"BUY",d("10000"),
                d("1.1"),d("1.101"),d("1000"),d("100"),BigDecimal.ONE,BigDecimal.ONE,d("30"));
        same(d("1"),manual.get("profit")); same(d("-29"),manual.get("net"));
    }

    @Test void fxMarginIdentityRequiresConsistentExchangeRates() {
        Fixture f = new Fixture("JPY=X","USD","JPY","150","0.0065");
        f.symbol.setLotSize(d("100000"));
        same(d("975"),f.open("1","100","BUY").getMargin()); // Base USD rule would be exactly 1000.
    }

}
