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
    @org.junit.jupiter.api.BeforeEach void tenant(){com.gtcfesk.exchange.tenant.TenantContext.open(1L);}
    @org.junit.jupiter.api.AfterEach void clear(){com.gtcfesk.exchange.tenant.TenantContext.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @SuppressWarnings({"unchecked","rawtypes"}) javax.persistence.EntityManager lookup(WithdrawRecord record){
        javax.persistence.EntityManager em=mock(javax.persistence.EntityManager.class);javax.persistence.metamodel.Metamodel model=mock(javax.persistence.metamodel.Metamodel.class);javax.persistence.metamodel.EntityType<WithdrawRecord> type=mock(javax.persistence.metamodel.EntityType.class);javax.persistence.metamodel.SingularAttribute key=mock(javax.persistence.metamodel.SingularAttribute.class);
        when(em.getMetamodel()).thenReturn(model);when(model.entity(WithdrawRecord.class)).thenReturn(type);when(type.getSingularAttributes()).thenReturn(Collections.singleton(key));when(key.isId()).thenReturn(true);when(key.getName()).thenReturn("id");
        javax.persistence.criteria.CriteriaBuilder builder=mock(javax.persistence.criteria.CriteriaBuilder.class);javax.persistence.criteria.CriteriaQuery<WithdrawRecord> query=mock(javax.persistence.criteria.CriteriaQuery.class);javax.persistence.criteria.Root<WithdrawRecord> root=mock(javax.persistence.criteria.Root.class);javax.persistence.TypedQuery<WithdrawRecord> result=mock(javax.persistence.TypedQuery.class);
        when(em.getCriteriaBuilder()).thenReturn(builder);when(builder.createQuery(WithdrawRecord.class)).thenReturn(query);when(query.from(WithdrawRecord.class)).thenReturn(root);when(query.select(root)).thenReturn(query);when(em.createQuery(query)).thenReturn(result);when(result.setLockMode(any())).thenReturn(result);when(result.setMaxResults(1)).thenReturn(result);when(result.getResultList()).thenReturn(Collections.singletonList(record));return em;
    }

    @Test void freezesUsdAndRefundsOrCompletesUsingTheLockedAmount() {
        for (boolean reject : new boolean[]{true, false}) {
            ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
            when(market.requireConversionRate("JPY", "yahoo")).thenReturn(new BigDecimal("0.00625"));
            WithdrawRecordRepository records = mock(WithdrawRecordRepository.class);
            AssetAccountRepository accounts = mock(AssetAccountRepository.class);
            UserBankCardRepository cards = mock(UserBankCardRepository.class);
            AssetAccount fund = new AssetAccount(); fund.setCoin("FUND"); fund.setAvailable(new BigDecimal("120")); fund.setFrozen(new BigDecimal("5"));
            when(accounts.lockByUserId(7L)).thenReturn(Collections.singletonList(fund));
            UserBankCard card = new UserBankCard(); card.setRecipientAccount("123"); card.setCurrency("USD");
            when(cards.findByTenantIdAndUserId(1L, 7L)).thenReturn(Collections.singletonList(card));
            WithdrawController controller = new WithdrawController(records, accounts, mock(UserDigitalAddressRepository.class), cards, new FiatCurrencyService(market));
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"audit",mock(com.gtcfesk.exchange.control.ControlAuditService.class));
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"tenantPolicy",mock(com.gtcfesk.exchange.control.TenantPolicyService.class));
            Map<String, Object> req = new HashMap<>();
            req.put("requestId","fiat-withdraw-test-key");
            com.gtcfesk.exchange.repository.UserAccountRepository users=mock(com.gtcfesk.exchange.repository.UserAccountRepository.class);UserAccount customer=new UserAccount();customer.setId(7L);when(users.lockById(7L)).thenReturn(Optional.of(customer));when(users.findByTenantIdAndId(1L,7L)).thenReturn(Optional.of(customer));org.springframework.test.util.ReflectionTestUtils.setField(controller,"users",users);
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
            when(records.findByTenantIdAndId(1L, 1L)).thenReturn(Optional.of(record));
            when(market.requireConversionRate("JPY", "yahoo")).thenThrow(new BusinessException("expired"));
            when(records.findOwnerIdById(1L)).thenReturn(Optional.of(7L));
            when(records.lockById(1L)).thenReturn(Optional.of(record));
            WithdrawReviewController review = new WithdrawReviewController(records, accounts, cards, users, null, null, null);
            org.springframework.test.util.ReflectionTestUtils.setField(review,"controlAudit",mock(com.gtcfesk.exchange.control.ControlAuditService.class));
            org.springframework.test.util.ReflectionTestUtils.setField(review,"em",lookup(record));
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

    @Test void disabledWithdrawRejectsBeforeTouchingMoney(){WithdrawRecordRepository records=mock(WithdrawRecordRepository.class);AssetAccountRepository accounts=mock(AssetAccountRepository.class);WithdrawController controller=new WithdrawController(records,accounts,mock(UserDigitalAddressRepository.class),mock(UserBankCardRepository.class),mock(FiatCurrencyService.class));com.gtcfesk.exchange.control.TenantPolicyService policy=mock(com.gtcfesk.exchange.control.TenantPolicyService.class);org.springframework.test.util.ReflectionTestUtils.setField(controller,"tenantPolicy",policy);doThrow(new org.springframework.security.access.AccessDeniedException("disabled")).when(policy).requireNewBusiness("withdraw");assertThrows(RuntimeException.class,()->controller.submitWithdraw(new UsernamePasswordAuthenticationToken("7",""),Collections.emptyMap()));verifyNoInteractions(records,accounts);}
    @Test void invalidRateAmountCurrencyAndInsufficientUsdNeverFreezeFunds() {
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        when(market.requireConversionRate("EUR", "yahoo")).thenReturn(new BigDecimal("1.2"));
        when(market.requireConversionRate("JPY", "yahoo")).thenThrow(new BusinessException("expired"));
        AssetAccountRepository accounts = mock(AssetAccountRepository.class);
        AssetAccount fund = new AssetAccount(); fund.setCoin("FUND"); fund.setAvailable(new BigDecimal("100"));
        when(accounts.lockByUserId(7L)).thenReturn(Collections.singletonList(fund));
        WithdrawRecordRepository records = mock(WithdrawRecordRepository.class);
        WithdrawController controller = new WithdrawController(records, accounts, mock(UserDigitalAddressRepository.class), mock(UserBankCardRepository.class), new FiatCurrencyService(market));
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"tenantPolicy",mock(com.gtcfesk.exchange.control.TenantPolicyService.class));
        for (String[] input : new String[][]{{"EUR","100"},{"JPY","16000"},{"BTC","1"},{"USD","0"},{"EUR","-1"}}) {
            Map<String,Object> req = new HashMap<>();
            req.put("type","bank"); req.put("network","USD"); req.put("address","123"); req.put("currency",input[0]); req.put("amount",input[1]);
            assertEquals(400, controller.submitWithdraw(new UsernamePasswordAuthenticationToken("7", ""), req).getStatusCodeValue());
        }
        verify(accounts, never()).save(any()); verify(records, never()).save(any());
        assertEquals(new BigDecimal("100"), fund.getAvailable()); assertEquals(BigDecimal.ZERO, fund.getFrozen());
    }
}
