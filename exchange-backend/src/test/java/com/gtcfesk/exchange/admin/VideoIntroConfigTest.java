package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.entity.SystemConfig;
import com.gtcfesk.exchange.repository.SystemConfigRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static com.gtcfesk.exchange.admin.SystemConfigService.VIDEO_INTRO_URL_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoIntroConfigTest {
    private final SystemConfigService configs = new SystemConfigService();
    private final SystemConfigRepository repository = mock(SystemConfigRepository.class);
    private final TenantPolicyService policy = mock(TenantPolicyService.class);

    @BeforeEach void setup() {
        TenantContext.open(42L);
        ReflectionTestUtils.setField(configs, "systemConfigRepository", repository);
        ReflectionTestUtils.setField(configs, "tenantPolicy", policy);
        when(policy.effectiveConfig(anyString(), any())).thenAnswer(call -> call.getArgument(1));
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void addressesRoundTripAndCanBeCleared() {
        ArgumentCaptor<SystemConfig> saved = ArgumentCaptor.forClass(SystemConfig.class);
        for (String value : new String[]{" https://cdn.example.com/intro.mp4?token=test&expires=123 ",
                "http://media.example.com:8080/intro.webm#t=10", "HTTPS://media.example.com/watch?id=1", "", "   ", null}) {
            configs.saveConfig(VIDEO_INTRO_URL_KEY, value, "视频简介地址");
            verify(repository, atLeastOnce()).save(saved.capture());
            SystemConfig row = saved.getValue();
            String expected = value == null ? "" : value.trim();
            assertEquals(VIDEO_INTRO_URL_KEY, row.getConfigKey());
            assertEquals("视频简介地址", row.getDescription());
            assertEquals(expected, row.getConfigValue());
            when(repository.findByTenantIdAndConfigKey(42L, VIDEO_INTRO_URL_KEY)).thenReturn(Optional.of(row));
            assertEquals(expected, configs.getConfigValue(VIDEO_INTRO_URL_KEY));
            verify(policy).requireConfigChange(VIDEO_INTRO_URL_KEY, expected);
            clearInvocations(repository, policy);
        }
        TenantContext.clear();
        TenantContext.open(43L);
        assertNull(configs.getConfigValue(VIDEO_INTRO_URL_KEY));
        verify(repository).findByTenantIdAndConfigKey(43L, VIDEO_INTRO_URL_KEY);
    }

    @Test void invalidAddressesNeverReachStorage() {
        for (String value : new String[]{"/intro.mp4", "//cdn.example.com/intro.mp4", "example.com/intro.mp4",
                "javascript:alert(1)", "data:video/mp4;base64,AAAA", "file:///intro.mp4", "ftp://cdn.example.com/intro.mp4",
                "https://", "https://bad host/intro.mp4", "https://user:pass@cdn.example.com/intro.mp4",
                "https://cdn.example.com:0/intro.mp4", "https://cdn.example.com:65536/intro.mp4"})
            assertThrows(BusinessException.class, () -> configs.saveConfig(VIDEO_INTRO_URL_KEY, value, null), value);
        verifyNoInteractions(repository);
    }

    @Test void lockedConfigurationCannotBeWritten() {
        String url = "https://cdn.example.com/intro.mp4";
        doThrow(new AccessDeniedException("配置已由总控锁定"))
                .when(policy).requireConfigChange(VIDEO_INTRO_URL_KEY, url);
        assertThrows(AccessDeniedException.class, () -> configs.saveConfig(VIDEO_INTRO_URL_KEY, url, null));
        verifyNoInteractions(repository);
    }
}
