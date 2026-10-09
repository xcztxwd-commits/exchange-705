package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.OrderRequest;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WithdrawChannelsTest {
    final SystemConfigService configs = mock(SystemConfigService.class);
    final WithdrawRecordRepository records = mock(WithdrawRecordRepository.class);
    final AssetAccountRepository assets = mock(AssetAccountRepository.class);
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final com.gtcfesk.exchange.market.ForexQuoteMarketService market = mock(com.gtcfesk.exchange.market.ForexQuoteMarketService.class);
    final WithdrawController controller = new WithdrawController(records, assets, mock(UserDigitalAddressRepository.class),
            mock(UserBankCardRepository.class), new FiatCurrencyService(market), configs);
    final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("7", "");

    @BeforeEach void setup() {
        TenantContext.open(42L);
        ReflectionTestUtils.setField(controller, "tenantPolicy", mock(TenantPolicyService.class));
        ReflectionTestUtils.setField(controller, "users", users);
        when(market.requireConversionRate("USD", "yahoo")).thenReturn(BigDecimal.ONE);
    }
    @AfterEach void cleanup() { TenantContext.clear(); }

    Map<String, Object> request(String type) {
        Map<String, Object> request = new HashMap<>();
        request.put("type", type); request.put("network", "USD"); request.put("currency", "USD");
        request.put("amount", "10"); request.put("address", "FIXTURE-ONLY"); request.put("requestId", "withdraw-channel-fixture");
        return request;
    }

    @Test void missingConfigDefaultsOnAndAllChannelCombinationsAreReturned() {
        Map<?, ?> defaults = (Map<?, ?>) controller.getChannels().getBody();
        assertEquals(true, defaults.get("digital")); assertEquals(true, defaults.get("bank"));
        for (boolean digital : new boolean[]{true, false}) for (boolean bank : new boolean[]{true, false}) {
            when(configs.getConfigValue("withdraw.digital.enabled")).thenReturn(String.valueOf(digital));
            when(configs.getConfigValue("withdraw.bank.enabled")).thenReturn(String.valueOf(bank));
            Map<?, ?> result = (Map<?, ?>) controller.getChannels().getBody();
            assertEquals(digital, result.get("digital")); assertEquals(bank, result.get("bank")); assertEquals(true, result.get("success"));
        }
        when(configs.getConfigValue("withdraw.digital.enabled")).thenReturn("DENY");
        assertEquals(false, ((Map<?, ?>) controller.getChannels().getBody()).get("digital"));
    }

    @Test void disabledChannelsRejectDirectSubmissionAndCalculationWithoutTouchingMoney() {
        for (String type : new String[]{"digital", "bank"}) {
            when(configs.getConfigValue("withdraw." + type + ".enabled")).thenReturn("false");
            assertEquals(400, controller.submitWithdraw(auth, request(type)).getStatusCodeValue());
            assertEquals(400, controller.calculateAmount(request(type)).getStatusCodeValue());
        }
        verifyNoInteractions(assets, users);
        verify(records, never()).save(any());
    }

    @Test void closingChannelAfterInitialReadRejectsBeforeFreezingOrCreatingOrder() {
        when(configs.getConfigValue("withdraw.bank.enabled")).thenReturn("true");
        when(configs.getCurrentConfigValue("withdraw.bank.enabled")).thenReturn("false");
        UserAccount user = new UserAccount(); user.setId(7L);
        when(users.lockById(7L)).thenReturn(Optional.of(user));
        AssetAccount account = new AssetAccount(); account.setCoin("FUND"); account.setAvailable(new BigDecimal("100"));
        when(assets.lockByUserId(7L)).thenReturn(Collections.singletonList(account));
        assertEquals(400, controller.submitWithdraw(auth, request("bank")).getStatusCodeValue());
        org.mockito.InOrder order = inOrder(assets, configs);
        order.verify(assets).lockByUserId(7L);
        order.verify(configs).getCurrentConfigValue("withdraw.bank.enabled");
        assertEquals(new BigDecimal("100"), account.getAvailable()); assertEquals(BigDecimal.ZERO, account.getFrozen());
        verify(assets, never()).save(any()); verify(records, never()).save(any());
    }

    @Test void existingReceiptCanBeReplayedAfterChannelIsClosedWithoutAnotherFreeze() {
        when(configs.getConfigValue("withdraw.bank.enabled")).thenReturn("false");
        when(configs.getCurrentConfigValue("withdraw.bank.enabled")).thenReturn("false");
        UserAccount user = new UserAccount(); user.setId(7L);
        when(users.lockById(7L)).thenReturn(Optional.of(user));
        WithdrawRecord receipt = new WithdrawRecord(); receipt.setId(9L);
        receipt.setRequestHash(OrderRequest.hash("withdraw", "bank", "USD", new BigDecimal("10"), "USD", "FIXTURE-ONLY", null));
        when(records.findReplayId(42L, 7L, "withdraw-channel-fixture")).thenReturn(Optional.of(9L));
        when(records.findByTenantIdAndUserIdAndRequestKey(42L, 7L, "withdraw-channel-fixture")).thenReturn(Optional.of(receipt));
        assertEquals(200, controller.submitWithdraw(auth, request("bank")).getStatusCodeValue());
        verifyNoInteractions(configs);
        verify(assets, never()).save(any()); verify(records, never()).save(any());
    }

    @Test void switchesPersistPerTenantRejectInvalidValuesAndHonorControlLock() {
        SystemConfigService service = new SystemConfigService();
        SystemConfigRepository repository = mock(SystemConfigRepository.class);
        TenantPolicyService policy = mock(TenantPolicyService.class);
        ReflectionTestUtils.setField(service, "systemConfigRepository", repository);
        ReflectionTestUtils.setField(service, "tenantPolicy", policy);
        when(policy.effectiveConfig(anyString(), any())).thenAnswer(call -> call.getArgument(1));
        for (String type : new String[]{"digital", "bank"}) {
            String key = "withdraw." + type + ".enabled";
            for (String invalid : new String[]{null, "", "0", "FALSE", "yes"})
                assertThrows(BusinessException.class, () -> service.saveConfig(key, invalid, null));
            verify(repository, never()).save(any());
        }
        org.mockito.ArgumentCaptor<SystemConfig> saved = org.mockito.ArgumentCaptor.forClass(SystemConfig.class);
        service.saveConfig("withdraw.bank.enabled", "false", "银行卡出金");
        verify(repository).save(saved.capture());
        when(repository.findByTenantIdAndConfigKey(42L, "withdraw.bank.enabled")).thenReturn(Optional.of(saved.getValue()));
        assertEquals("false", service.getConfigValue("withdraw.bank.enabled"));
        TenantContext.clear(); TenantContext.open(43L);
        assertNull(service.getConfigValue("withdraw.bank.enabled"));
        when(policy.effectiveConfig("withdraw.bank.enabled", null)).thenReturn("false");
        assertEquals("false", service.getConfigValue("withdraw.bank.enabled"));
        doThrow(new org.springframework.security.access.AccessDeniedException("locked")).when(policy).requireConfigChange("withdraw.bank.enabled", "true");
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.saveConfig("withdraw.bank.enabled", "true", null));
        verify(repository, times(1)).save(any());
    }
}
