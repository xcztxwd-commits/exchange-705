package com.gtcfesk.exchange.security;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.activity.*;
import com.gtcfesk.exchange.common.KycRequiredException;
import com.gtcfesk.exchange.config.TradeKycGate;
import com.gtcfesk.exchange.entity.KycRecord;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.user.KycIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeKycSwitchTest {
    @Test void switchAndLatestApprovalAreAuthoritativeEvenWithTrialFunds() {
        KycRecordRepository records = mock(KycRecordRepository.class);
        SystemConfigService configs = mock(SystemConfigService.class);
        KycIdentityService identity = new KycIdentityService(records);
        ReflectionTestUtils.setField(identity, "configs", configs);
        TrialAccountRepository trials = mock(TrialAccountRepository.class);
        TrialAccount trial = new TrialAccount(); trial.setAvailable(new java.math.BigDecimal("1000"));
        when(trials.findByTenantIdAndId(1L, 7L)).thenReturn(Optional.of(trial));
        TrialFunds funds = new TrialFunds(trials, mock(TrialLedgerRepository.class),
                mock(UserAccountRepository.class), mock(AssetAccountRepository.class), identity);
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            when(records.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(1L, 7L)).thenReturn(Optional.empty());
            assertEquals(new java.math.BigDecimal("1000"), funds.available(7L));
            assertTrue(identity.tradingKycRequired());
            assertThrows(KycRequiredException.class, () -> funds.requireTrade(7L));
            for (String status : new String[]{"PENDING", "REJECTED", "APPROVED"}) {
                KycRecord record = new KycRecord(); record.setStatus(status);
                when(records.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(1L, 7L)).thenReturn(Optional.of(record));
                assertEquals("APPROVED".equals(status), funds.canTrade(7L));
            }
            when(records.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(1L, 7L)).thenReturn(Optional.empty());
            when(configs.getConfigValue(KycIdentityService.TRADE_KYC_KEY)).thenReturn("false");
            assertTrue(funds.canTrade(7L));
            assertDoesNotThrow(() -> funds.requireTrade(7L));
            // Disabling trading KYC must not disable withdrawal identity checks.
            assertThrows(KycRequiredException.class, () -> identity.requireApproved(7L));
            when(configs.getConfigValue(KycIdentityService.TRADE_KYC_KEY)).thenReturn("true");
            assertFalse(funds.canTrade(7L));
            assertThrows(KycRequiredException.class, () -> funds.requireTrade(7L));
        }
    }

    @Test void tradingWritesAreGuardedWhileReadsAreNot() {
        KycIdentityService identity = mock(KycIdentityService.class);
        TradeKycGate gate = new TradeKycGate(identity);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "7");
        request.setMethod("GET");
        assertTrue(gate.preHandle(request, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(identity);
        request.setMethod("POST");
        doThrow(new KycRequiredException("NOT_VERIFIED")).when(identity).requireTradingApproved(7L);
        assertThrows(KycRequiredException.class, () -> gate.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test void invalidSwitchValuesCannotBeSaved() {
        SystemConfigService configs = new SystemConfigService();
        ReflectionTestUtils.setField(configs, "tenantPolicy", mock(com.gtcfesk.exchange.control.TenantPolicyService.class));
        SystemConfigRepository repository = mock(SystemConfigRepository.class);
        ReflectionTestUtils.setField(configs, "systemConfigRepository", repository);
        for (String value : new String[]{null, "", "0", "TRUE", "invalid"}) {
            assertThrows(com.gtcfesk.exchange.common.BusinessException.class,
                    () -> configs.saveConfig(KycIdentityService.TRADE_KYC_KEY, value, "未实名不可交易"));
        }
        verifyNoInteractions(repository);
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            when(repository.findByTenantIdAndConfigKey(1L, KycIdentityService.TRADE_KYC_KEY)).thenReturn(Optional.empty());
            configs.saveConfig(KycIdentityService.TRADE_KYC_KEY, "true", "未实名不可交易");
            configs.saveConfig(KycIdentityService.TRADE_KYC_KEY, "false", "未实名不可交易");
            verify(repository, times(2)).save(any(com.gtcfesk.exchange.entity.SystemConfig.class));
        }
    }
}
