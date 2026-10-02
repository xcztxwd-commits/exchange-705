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
class CryptoQuantityLifecycleTest {
 @Test void closeBothSidesAndCancelConserveFunds() {
  for(String side:Arrays.asList("BUY","SELL")) {
   Fixture f=fixture("BTC","80000","0.001");ContractOrder o=f.service.createOrder(1L,request(f,"0.01","100",side,"MARKET"));
   when(f.quotes.freshPrice("BTCUSDT")).thenReturn(d("80100"));f.service.closeOrder(1L,o.getId(),null);
   same(d("BUY".equals(side)?"1":"-1"),o.getProfit());same(d("BUY".equals(side)?"0.9997":"-1.0003"),f.account.getAvailable().subtract(f.initial));same(BigDecimal.ZERO,f.account.getFrozen());
   assertThrows(BusinessException.class,()->f.service.closeOrder(1L,o.getId(),null));
  }
  Fixture f=fixture("BTC","80000","0.001");ContractOrder o=f.service.createOrder(1L,request(f,"0.01","100","BUY","LIMIT"));
  f.service.cancelOrder(1L,o.getId());same(f.initial,f.account.getAvailable());same(BigDecimal.ZERO,f.account.getFrozen());assertThrows(BusinessException.class,()->f.service.cancelOrder(1L,o.getId()));
 }
 @Test void pendingUsesSnapshotAndUnfundedSellRemainsPending() {
  Fixture f=fixture("BTC","80000","0.001");ContractOrder o=f.service.createOrder(1L,request(f,"0.01","100","SELL","LIMIT"));
  f.symbol.setMinOrderNotional(d("100000"));f.symbol.setQuantityStep(d("100"));f.symbol.setLotSize(d("1000"));
  when(f.quotes.freshPrice("BTCUSDT")).thenReturn(d("80100"));
  com.gtcfesk.exchange.repository.KycRecordRepository records=mock(com.gtcfesk.exchange.repository.KycRecordRepository.class);
  KycRecord approved=new KycRecord();approved.setUserId(1L);approved.setStatus("APPROVED");
  when(records.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(1L, 1L)).thenReturn(Optional.of(approved));
  com.gtcfesk.exchange.user.KycIdentityService k=new com.gtcfesk.exchange.user.KycIdentityService(records);
  org.springframework.test.util.ReflectionTestUtils.setField(f.service,"identityService",k);
  assertFalse(k.simulationExempt());assertTrue(k.canUseTradingFunds(1L));
  f.account.setAvailable(BigDecimal.ZERO);
  assertEquals(0,(int)org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.service,"matchPendingLimitOrder",o.getId()));assertEquals("PENDING",o.getStatus());same(d("8.0003"),f.account.getFrozen());
  f.account.setAvailable(d("0.01"));assertEquals(1,(int)org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.service,"matchPendingLimitOrder",o.getId()));same(d("8.01"),o.getMargin());same(d("0.0003"),o.getFee());same(BigDecimal.ZERO,f.account.getAvailable());
 }
 @Test void unapprovedNonSimulationPendingOrderDoesNotMoveFunds() {
  for(String status:Arrays.asList("PENDING","REJECTED")) {
   Fixture f=fixture("BTC","80000","0.001");ContractOrder o=f.service.createOrder(1L,request(f,"0.01","100","SELL","LIMIT"));
   KycRecord record=new KycRecord();record.setUserId(1L);record.setStatus(status);
   com.gtcfesk.exchange.repository.KycRecordRepository records=mock(com.gtcfesk.exchange.repository.KycRecordRepository.class);
   when(records.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(1L, 1L)).thenReturn(Optional.of(record));
   com.gtcfesk.exchange.user.KycIdentityService k=new com.gtcfesk.exchange.user.KycIdentityService(records);
   org.springframework.test.util.ReflectionTestUtils.setField(f.service,"identityService",k);
   assertFalse(k.simulationExempt());assertFalse(k.canUseTradingFunds(1L));
   BigDecimal available=f.account.getAvailable(), frozen=f.account.getFrozen(), margin=o.getMargin(), fee=o.getFee();
   when(f.quotes.freshPrice("BTCUSDT")).thenReturn(d("80100"));
   assertEquals(0,(int)org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.service,"matchPendingLimitOrder",o.getId()));
   assertEquals("PENDING",o.getStatus());same(available,f.account.getAvailable());same(frozen,f.account.getFrozen());same(margin,o.getMargin());same(fee,o.getFee());
  }
 }
 @Test void manualUsesConfiguredStepAndFloorsBudget() {
  Map<String,BigDecimal> v=ManualOrderCalculation.calculate("QUANTITY",d("0.001"),"BUY",d("8.0003"),d("80000"),d("80100"),BigDecimal.ONE,d("100"),BigDecimal.ONE,BigDecimal.ONE,d("0.03"),d("80000"),d("0.001"));
  same(d("0.8"),v.get("margin"));same(d("0.00003"),v.get("fee"));same(d("0.09997"),v.get("net"));
  v=ManualOrderCalculation.calculate("PERCENT",d("100"),"BUY",d("8.000299"),d("80000"),d("80100"),BigDecimal.ONE,d("100"),BigDecimal.ONE,BigDecimal.ONE,d("0.03"),d("80000"),d("0.001"));same(d("0.009"),v.get("quantity"));
 }
}
