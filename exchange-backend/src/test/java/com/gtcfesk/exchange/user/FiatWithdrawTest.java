package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.WithdrawReviewController;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FiatWithdrawTest {
    @Test void freezesUsdAndRefundsOrCompletesUsingTheLockedAmount() {
        for (boolean reject : new boolean[]{true, false}) {
            ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
            when(market.requireConversionRate("JPY", "yahoo")).thenReturn(new BigDecimal("0.00625"));
            WithdrawRecordRepository records = mock(WithdrawRecordRepository.class);
            AssetAccountRepository accounts = mock(AssetAccountRepository.class);
            UserBankCardRepository cards = mock(UserBankCardRepository.class);
            AssetAccount fund = new AssetAccount(); fund.setAvailable(new BigDecimal("120")); fund.setFrozen(new BigDecimal("5"));
            when(accounts.findByUserIdAndCoin(7L, "FUND")).thenReturn(Optional.of(fund));
            UserBankCard card = new UserBankCard(); card.setRecipientAccount("123"); card.setCurrency("USD");
            when(cards.findByUserId(7L)).thenReturn(Collections.singletonList(card));
            WithdrawController controller = new WithdrawController(records, accounts, mock(UserDigitalAddressRepository.class), cards, new FiatCurrencyService(market));
            Map<String, Object> req = new HashMap<>();
            req.put("type", "bank"); req.put("network", "USD"); req.put("currency", "JPY"); req.put("amount", "16000"); req.put("address", "123");
            Map<?, ?> quote = (Map<?, ?>) controller.calculateAmount(req).getBody();
            assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) quote.get("actualAmount")));
            assertEquals("USD", quote.get("settlementCurrency"));
            org.mockito.ArgumentCaptor<WithdrawRecord> saved = org.mockito.ArgumentCaptor.forClass(WithdrawRecord.class);
            assertEquals(200, controller.submitWithdraw(new UsernamePasswordAuthenticationToken("7", ""), req).getStatusCodeValue());
            verify(records).save(saved.capture());
            WithdrawRecord record = saved.getValue();
            assertEquals("JPY", record.getCurrency()); assertEquals(new BigDecimal("16000"), record.getOriginalAmount());
            assertEquals(new BigDecimal("0.00625"), record.getExchangeRate());
            assertEquals(0, new BigDecimal("20").compareTo(fund.getAvailable()));
            assertEquals(0, new BigDecimal("105").compareTo(fund.getFrozen()));
            when(records.findById(1L)).thenReturn(Optional.of(record));
            when(market.requireConversionRate("JPY", "yahoo")).thenThrow(new BusinessException("expired"));
            WithdrawReviewController review = new WithdrawReviewController(records, accounts, cards, null, null, null, null);
            if (reject) {
                WithdrawReviewController.RejectRequest rejection = new WithdrawReviewController.RejectRequest(); rejection.setRemark("test");
                assertEquals(200, review.rejectWithdraw(1L, rejection, null).getStatusCodeValue());
                assertEquals(0, new BigDecimal("120").compareTo(fund.getAvailable()));
                assertEquals(400, review.rejectWithdraw(1L, rejection, null).getStatusCodeValue());
            } else {
                assertEquals(200, review.approveWithdraw(1L, null, null).getStatusCodeValue());
                assertEquals(200, review.completeWithdraw(1L, null).getStatusCodeValue());
                assertEquals(0, new BigDecimal("20").compareTo(fund.getAvailable()));
                assertEquals(400, review.completeWithdraw(1L, null).getStatusCodeValue());
            }
            assertEquals(0, new BigDecimal("5").compareTo(fund.getFrozen()));
        }
    }

    @Test void invalidRateAmountCurrencyAndInsufficientUsdNeverFreezeFunds() {
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        when(market.requireConversionRate("EUR", "yahoo")).thenReturn(new BigDecimal("1.2"));
        when(market.requireConversionRate("JPY", "yahoo")).thenThrow(new BusinessException("expired"));
        AssetAccountRepository accounts = mock(AssetAccountRepository.class);
        AssetAccount fund = new AssetAccount(); fund.setAvailable(new BigDecimal("100"));
        when(accounts.findByUserIdAndCoin(7L, "FUND")).thenReturn(Optional.of(fund));
        WithdrawRecordRepository records = mock(WithdrawRecordRepository.class);
        WithdrawController controller = new WithdrawController(records, accounts, mock(UserDigitalAddressRepository.class), mock(UserBankCardRepository.class), new FiatCurrencyService(market));
        for (String[] input : new String[][]{{"EUR","100"},{"JPY","16000"},{"BTC","1"},{"USD","0"},{"EUR","-1"}}) {
            Map<String,Object> req = new HashMap<>();
            req.put("type","bank"); req.put("network","USD"); req.put("address","123"); req.put("currency",input[0]); req.put("amount",input[1]);
            assertEquals(400, controller.submitWithdraw(new UsernamePasswordAuthenticationToken("7", ""), req).getStatusCodeValue());
        }
        verify(accounts, never()).save(any()); verify(records, never()).save(any());
        assertEquals(new BigDecimal("100"), fund.getAvailable()); assertEquals(BigDecimal.ZERO, fund.getFrozen());
    }
}
