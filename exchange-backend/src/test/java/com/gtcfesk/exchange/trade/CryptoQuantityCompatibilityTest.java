package com.gtcfesk.exchange.trade;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest;
import org.junit.jupiter.api.*;
import java.math.*;
import java.util.*;
import java.util.stream.Stream;
import static com.gtcfesk.exchange.trade.FeeCalculationAuditTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.gtcfesk.exchange.trade.CryptoQuantityRulesTest.*;
@org.junit.jupiter.api.extension.ExtendWith(CalculationTenantExtension.class)
class CryptoQuantityCompatibilityTest {
 @Test void oldAndNewExposureAndNullSnapshotStayDistinct() {
  Fixture old=new Fixture("BTCUSDT","BTC","USDT","80000","1");old.symbol.setSourceCategory("Crypto");
  ContractOrder o=old.open("0.01","100","BUY");same(d("0.3"),o.getFee());same(d("1000"),o.getLotSize());assertNull(o.getQuantityUnitType());
  old.symbol.setLotSize(BigDecimal.ONE);old.symbol.setFeeMultiplier(d("0.03"));when(old.quotes.freshPrice("BTCUSDT")).thenReturn(d("80100"));old.service.closeOrder(1L,o.getId(),null);same(d("1000"),o.getProfit());same(d("8000"),o.getMargin());
  Fixture fresh=fixture("BTC","80000","0.001");ContractOrder n=fresh.service.createOrder(1L,request(fresh,"10","100","BUY","MARKET"));same(o.getFee(),n.getFee());same(o.getMargin(),n.getMargin());
  ContractOrder legacy=new ContractOrder();legacy.setSide("BUY");legacy.setOpenPrice(d("80000"));legacy.setQuantity(d("0.01"));legacy.setLeverage(d("100"));same(d("100"),ContractValuation.quoteProfit(legacy,d("80100")));
 }
 @Test void forexAndStockRemainTheirOwnRules() {
  Fixture fx=FxStandardContractTest.fx("JPY=X","USD","JPY","157.2","0.0063");ContractOrder o=fx.open("1","100","BUY");same(d("1000"),o.getMargin());same(d("7"),o.getFee());
  Fixture stock=new Fixture("AAPL","AAPL","USD","200","1");o=stock.open("1","100","BUY");same(d("2000"),o.getMargin());same(d("30"),o.getFee());assertNull(o.getQuantityUnitType());
 }
}
