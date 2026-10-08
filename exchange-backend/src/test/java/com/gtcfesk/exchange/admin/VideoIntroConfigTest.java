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

    @Test void multilingualConfigurationIsAtomicAndTenantScoped() {
        String en = "/api/uploads/videos/42/staff/7/00000000-0000-0000-0000-000000000001.mp4";
        String fr = en.replace("000000000001.mp4", "000000000002.webm");
        String value = "{\"defaultLocale\":\"fr\",\"videos\":{\"en\":\"" + en + "\",\"fr\":\"" + fr + "\"}}";
        configs.saveConfig(VideoIntroSettings.KEY, value, "宣传视频");
        ArgumentCaptor<SystemConfig> saved = ArgumentCaptor.forClass(SystemConfig.class);
        verify(repository).save(saved.capture());
        assertEquals(value, saved.getValue().getConfigValue());
        verify(policy).requireConfigChange(VideoIntroSettings.KEY, value);
        com.fasterxml.jackson.databind.JsonNode settings = VideoIntroSettings.parse(value, 42L);
        assertEquals("en", VideoIntroSettings.language(settings, "en-US"));
        assertEquals("fr", VideoIntroSettings.language(settings, "ja"));
        assertEquals("fr", VideoIntroSettings.language(settings, "unknown"));
        assertEquals("fr", VideoIntroSettings.language(settings, null));
        assertTrue(VideoIntroSettings.published(value, en, 42L));
        assertFalse(VideoIntroSettings.published(value, en, 43L));
        assertThrows(BusinessException.class, () -> VideoIntroSettings.parse(value, 43L));
        String chinese = "{\"defaultLocale\":\"zh-TW\",\"videos\":{\"zh-TW\":\"" + en + "\"}}";
        assertEquals("zh-TW", VideoIntroSettings.language(VideoIntroSettings.parse(chinese, 42L), "zh_CN"));
    }

    @Test void invalidLanguageBindingsAndMissingFallbackNeverReachStorage() {
        String url = "/api/uploads/videos/42/staff/7/00000000-0000-0000-0000-000000000001.mp4";
        for (String value : new String[]{null, "", "[]", "null", "{\"defaultLocale\":\"xx\",\"videos\":{}}",
                "{\"defaultLocale\":\"en\",\"videos\":{\"fr\":\"" + url + "\"}}",
                "{\"defaultLocale\":\"en\",\"videos\":{\"en\":\"" + url.replace("/42/", "/43/") + "\"}}",
                "{\"defaultLocale\":\"en\",\"videos\":{\"en\":\"https://cdn.example.com/intro.mp4\"}}",
                "{\"defaultLocale\":\"en\",\"videos\":{\"en\":\"" + url + "\",\"xx\":\"" + url + "\"}}",
                "{\"defaultLocale\":\"en\",\"videos\":{},\"extra\":true}",
                "{\"defaultLocale\":\"en\",\"defaultLocale\":\"fr\",\"videos\":{}}"})
            assertThrows(BusinessException.class, () -> configs.saveConfig(VideoIntroSettings.KEY, value, null), value);
        verifyNoInteractions(repository);
        assertEquals(0, VideoIntroSettings.parse("{\"defaultLocale\":\"en\",\"videos\":{}}", 42L).path("videos").size());
    }
}
