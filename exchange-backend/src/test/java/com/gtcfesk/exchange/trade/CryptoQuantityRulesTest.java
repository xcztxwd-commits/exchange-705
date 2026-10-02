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
@org.junit.jupiter.api.extension.ExtendWith(CalculationTenantExtension.class)
class CryptoQuantityRulesTest {
 static Fixture fixture(String base,String price,String step) {
  Fixture f=new Fixture(base+"USDT",base,"USDT",price,"1");
  f.symbol.setSourceCategory("Crypto"); f.symbol.setLotSize(BigDecimal.ONE);f.symbol.setFeeMultiplier(d("0.03"));
  f.symbol.setQuantityUnitType("BASE_ASSET");f.symbol.setSpecVersion(1L);f.symbol.setMinOrderQuantity(d(step));f.symbol.setQuantityStep(d(step));f.symbol.setMinOrderNotional(d("10"));
  return f;
 }
 static CreateContractOrderRequest request(Fixture f,String q,String lev,String side,String type) {
  CreateContractOrderRequest r=f.request(q,lev,side,type);r.setSpecVersion(1L);r.setQuantityUnitType("BASE_ASSET");return r;
 }
 @TestFactory Stream<DynamicTest> matrix() {
  List<DynamicTest> tests=new ArrayList<>();
  String[][] assets={{"BTC","80000","0.001","0.001","0.002","0.01","0.1","10"},{"ETH","2500","0.001","0.004","0.005","0.01","0.1","10"},{"SOL","100","0.01","0.10","0.11","0.15","1","10"}};
  for(String[] a:assets)for(int i=3;i<a.length;i++)for(String lev:Arrays.asList("1","5","10","20","50","100","DEFAULT"))for(String side:Arrays.asList("BUY","SELL"))for(String type:Arrays.asList("MARKET","LIMIT")) {
   String q=a[i];tests.add(DynamicTest.dynamicTest(a[0]+"/"+q+"/"+lev+"/"+side+"/"+type,()->{
    Fixture f=fixture(a[0],a[1],a[2]);CreateContractOrderRequest r=request(f,q,"100",side,type);r.setLeverage("DEFAULT".equals(lev)?null:d(lev));
    ContractOrder o=f.service.createOrder(1L,r);
    BigDecimal margin=d(q).multiply(d(a[1])).divide("DEFAULT".equals(lev)?d("100"):d(lev),16,RoundingMode.CEILING),fee=d(q).multiply(d("0.03"));
    same(margin,o.getMargin());same(fee,o.getFee());same(margin.add(fee),f.account.getFrozen());same(f.initial.subtract(margin).subtract(fee),f.account.getAvailable());
    same(BigDecimal.ONE,o.getLotSize());assertEquals("BASE_ASSET",o.getQuantityUnitType());assertEquals(a[0],o.getQuantityAsset());assertEquals(1L,o.getSpecVersion());
   }));
  }
  return tests.stream();
 }
 @Test void rejectionBeforeFundsAndIrregularStep() {
  Fixture f=fixture("BTC","80000","0.001");
  for(String q:Arrays.asList("0.0009","0.0015","0","-1","0.00000000000000001","10000000000000000"))assertThrows(BusinessException.class,()->f.service.createOrder(1L,request(f,q,"100","BUY","MARKET")));
  for(String value:Arrays.asList("NaN","Infinity","wat"))assertThrows(NumberFormatException.class,()->new BigDecimal(value));
  QuantityRules.quantity(d("0.015"),d("0.005"),d("0.005"));assertThrows(BusinessException.class,()->QuantityRules.quantity(d("0.016"),d("0.005"),d("0.005")));
  same(f.initial,f.account.getAvailable());same(BigDecimal.ZERO,f.account.getFrozen());verify(f.orders,never()).save(any());
 }
 @Test void protocolAndMinimumAndInvalidConfig() {
  Fixture f=fixture("ETH","2500","0.001");
  CreateContractOrderRequest r=request(f,"0.004","100","BUY","MARKET");
  r.setSpecVersion(null);assertThrows(BusinessException.class,()->f.service.createOrder(1L,r));
  r.setSpecVersion(2L);assertThrows(BusinessException.class,()->f.service.createOrder(1L,r));
  r.setSpecVersion(1L);r.setQuantityUnitType("LOT");assertThrows(BusinessException.class,()->f.service.createOrder(1L,r));
  assertThrows(BusinessException.class,()->f.service.createOrder(1L,request(f,"0.001","100","BUY","MARKET")));
  same(f.initial,f.account.getAvailable());verify(f.orders,never()).save(any());
  f.symbol.setLotSize(d("1000"));assertThrows(BusinessException.class,()->QuantityRules.configuration(f.symbol));
  f.symbol.setLotSize(BigDecimal.ONE);f.symbol.setMinOrderNotional(null);assertThrows(BusinessException.class,()->QuantityRules.configuration(f.symbol));
  Fixture sol=fixture("SOL","100","0.01");assertThrows(BusinessException.class,()->sol.service.createOrder(1L,request(sol,"0.01","20","BUY","MARKET")));
 }
 @Test void exactBudgetAndStalePrice() {
  Fixture f=fixture("BTC","80000","0.001");f.account.setAvailable(d("8.0002999999999999"));
  assertThrows(BusinessException.class,()->f.service.createOrder(1L,request(f,"0.01","100","BUY","MARKET")));
  f.account.setAvailable(d("8.0003"));ContractOrder o=f.service.createOrder(1L,request(f,"0.01","100","BUY","MARKET"));same(BigDecimal.ZERO,f.account.getAvailable());same(d("8.0003"),f.account.getFrozen());
  when(f.quotes.freshPrice("BTCUSDT")).thenReturn(null);assertThrows(BusinessException.class,()->f.service.closeOrder(1L,o.getId(),null));assertEquals("OPEN",o.getStatus());
 }
}
