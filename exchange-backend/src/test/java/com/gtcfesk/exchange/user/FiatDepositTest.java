package com.gtcfesk.exchange.user;
import com.gtcfesk.exchange.admin.AdminUserService;
import com.gtcfesk.exchange.admin.dto.UpdateUserBalanceRequest;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FiatDepositTest extends DepositOrderServiceTest {
 @Test void depositLocksRateAndReviewCreditsUsdOnce(){
  when(market.requireConversionRate("JPY","yahoo")).thenReturn(new BigDecimal("0.00625"));
  DepositOrderRequest r=request();r.type="bank";r.currency="JPY";r.amount=new BigDecimal("16000");
  DepositRecord d=service.submit(user.getId(),r);equal("100",d.getAmount());equal("16000",d.getOriginalAmount());equal("0.00625",d.getExchangeRate());assertEquals("JPY",d.getCurrency());
  when(market.requireConversionRate("JPY","yahoo")).thenThrow(new BusinessException("expired"));
  service.review(d.getId(),true,null);equal("125",balance("FUND"));assertThrows(RuntimeException.class,()->service.review(d.getId(),true,null));
  r.idempotencyKey=java.util.UUID.randomUUID().toString();assertThrows(RuntimeException.class,()->service.submit(user.getId(),r));
 }
 @Test void adminAddsConvertedAmountWithoutReplacingBalanceAndRejectsInvalidInput(){
  account("CONTRACT","25");AdminUserService adapter=new AdminUserService();ReflectionTestUtils.setField(adapter,"depositOrders",service);
  UpdateUserBalanceRequest r=new UpdateUserBalanceRequest();r.setUserId(user.getId());r.setAccount("CONTRACT");r.setCurrency("EUR");r.setAmount(new BigDecimal("100"));r.setRemark("fixture");r.setIdempotencyKey(java.util.UUID.randomUUID().toString());
  adapter.updateBalance(r);equal("135",balance("CONTRACT"));assertEquals(1,count());
  r.setAmount(BigDecimal.ZERO);assertThrows(BusinessException.class,()->adapter.updateBalance(r));r.setAmount(new BigDecimal("-1"));assertThrows(BusinessException.class,()->adapter.updateBalance(r));
  r.setCurrency("BTC");assertThrows(BusinessException.class,()->adapter.updateBalance(r));
  assertEquals("USD",fiat.currency(null));assertEquals(8,FiatCurrencyService.CURRENCIES.size());
  assertThrows(BusinessException.class,()->fiat.toUsd(new BigDecimal("9999999999999999"),new BigDecimal("2")));
  assertThrows(BusinessException.class,()->fiat.toUsd(new BigDecimal("0.0000000000000001"),new BigDecimal("0.001")));
 }
}
