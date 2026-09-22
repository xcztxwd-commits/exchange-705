package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.AdminUserService;
import com.gtcfesk.exchange.admin.DepositReviewService;
import com.gtcfesk.exchange.admin.dto.UpdateUserBalanceRequest;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FiatDepositTest {
    @Test void depositLocksRateAndReviewCreditsUsdOnce() {
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        when(market.requireConversionRate("JPY", "yahoo")).thenReturn(new BigDecimal("0.00625"));
        FiatCurrencyService fiat = new FiatCurrencyService(market);
        DepositRecordRepository records = mock(DepositRecordRepository.class);
        DepositController controller = new DepositController(mock(DepositSettingRepository.class), records, fiat);
        Map<String, Object> request = new HashMap<>();
        request.put("type", "bank"); request.put("network", "BANK"); request.put("address", "test");
        request.put("currency", "JPY"); request.put("amount", "16000");
        assertEquals(200, controller.submitDeposit(new UsernamePasswordAuthenticationToken("7", ""), request).getStatusCodeValue());
        ArgumentCaptor<DepositRecord> saved = ArgumentCaptor.forClass(DepositRecord.class);
        verify(records).save(saved.capture());
        DepositRecord record = saved.getValue();
        assertEquals(0, new BigDecimal("100").compareTo(record.getAmount()));
        assertEquals(new BigDecimal("16000"), record.getOriginalAmount());
        assertEquals("JPY", record.getCurrency());
        assertEquals(new BigDecimal("0.00625"), record.getExchangeRate());
        when(market.requireConversionRate("JPY", "yahoo")).thenThrow(new BusinessException("expired"));
        when(records.findById(1L)).thenReturn(Optional.of(record));
        AssetAccountRepository accounts = mock(AssetAccountRepository.class);
        AssetAccount account = new AssetAccount(); account.setAvailable(new BigDecimal("25"));
        when(accounts.findByUserIdAndCoin(7L, "FUND")).thenReturn(Optional.of(account));
        DepositReviewService review = new DepositReviewService(records, accounts, mock(UserAccountRepository.class));
        review.approveDeposit(1L);
        assertEquals(0, new BigDecimal("125").compareTo(account.getAvailable()));
        assertThrows(IllegalArgumentException.class, () -> review.approveDeposit(1L));
        assertEquals(400, controller.submitDeposit(new UsernamePasswordAuthenticationToken("7", ""), request).getStatusCodeValue());
        verify(accounts, times(1)).save(account);
    }

    @Test void adminAddsConvertedAmountWithoutReplacingBalanceAndRejectsInvalidInput() {
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        when(market.requireConversionRate("EUR", "yahoo")).thenReturn(new BigDecimal("1.1"));
        FiatCurrencyService fiat = new FiatCurrencyService(market);
        AssetAccountRepository accounts = mock(AssetAccountRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.existsById(7L)).thenReturn(true);
        AssetAccount account = new AssetAccount(); account.setAvailable(new BigDecimal("25"));
        when(accounts.findByUserIdAndCoin(7L, "CONTRACT")).thenReturn(Optional.of(account));
        AdminUserService admin = new AdminUserService();
        ReflectionTestUtils.setField(admin, "fiatCurrencyService", fiat);
        ReflectionTestUtils.setField(admin, "assetAccountRepository", accounts);
        ReflectionTestUtils.setField(admin, "userAccountRepository", users);
        UpdateUserBalanceRequest request = new UpdateUserBalanceRequest();
        request.setUserId(7L); request.setAccount("CONTRACT"); request.setCurrency("EUR"); request.setAmount(new BigDecimal("100"));
        admin.updateBalance(request);
        assertEquals(0, new BigDecimal("135").compareTo(account.getAvailable()));
        request.setAmount(BigDecimal.ZERO);
        assertThrows(BusinessException.class, () -> admin.updateBalance(request));
        request.setAmount(new BigDecimal("-1"));
        assertThrows(BusinessException.class, () -> admin.updateBalance(request));
        request.setCurrency("BTC");
        assertThrows(BusinessException.class, () -> admin.updateBalance(request));
        verify(accounts, times(1)).save(account);
        assertEquals("USD", fiat.currency(null));
        assertEquals(8, FiatCurrencyService.CURRENCIES.size());
        assertThrows(BusinessException.class, () -> fiat.toUsd(new BigDecimal("9999999999999999"), new BigDecimal("2")));
        assertThrows(BusinessException.class, () -> fiat.toUsd(new BigDecimal("0.0000000000000001"), new BigDecimal("0.001")));
    }
}
