package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.user.CustomerServiceController;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareTemplateConfigTest {
    @Test void defaultsAndConfiguredOrderReachUsers() {
        assertEquals(16, SystemConfigService.shareTemplates(null).size());
        SystemConfigService service = mock(SystemConfigService.class);
        when(service.getConfigValue("share.templates")).thenReturn("gold,light");
        assertEquals(java.util.Arrays.asList("gold", "light"), new CustomerServiceController(service, mock(com.gtcfesk.exchange.support.SupportSettings.class)).getShareTemplates("en", false).getBody());
    }

    @Test void rejectsEmptyDuplicateAndUnknownTemplatesBeforeSaving() {
        SystemConfigService service = new SystemConfigService();
        for (String value : new String[] { null, "", "light,light", "unknown", "light,", " light" }) {
            assertThrows(BusinessException.class, () -> service.saveConfig("share.templates", value, "test"));
        }
    }

    @Test void languageScopesAndOrderAreRespected() {
        String config = "{\"version\":2,\"templates\":[{\"id\":\"referenceWhite\",\"languages\":[\"ja\"]},{\"id\":\"gold\",\"languages\":[\"zh-TW\",\"ko\"]},{\"id\":\"light\",\"languages\":[\"*\"]}]}";
        assertEquals(java.util.Arrays.asList("referenceWhite", "light"), SystemConfigService.shareTemplates(config, "ja-JP"));
        assertEquals(java.util.Arrays.asList("gold", "light"), SystemConfigService.shareTemplates(config, "zh_CN"));
        assertEquals(java.util.Arrays.asList("light"), SystemConfigService.shareTemplates(config, "fr"));
        for (String language : SystemConfigService.SHARE_LANGUAGES) assertFalse(SystemConfigService.shareTemplates(config, language).isEmpty());
        assertEquals(java.util.Arrays.asList("gold", "light"), SystemConfigService.shareTemplates("gold,light", "ja"));
        SystemConfigService service = mock(SystemConfigService.class);
        when(service.getConfigValue("share.templates")).thenReturn(config);
        assertEquals(java.util.Arrays.asList("referenceWhite", "light"), new CustomerServiceController(service, mock(com.gtcfesk.exchange.support.SupportSettings.class)).getShareTemplates("ja", false).getBody());
    }

    @Test void focusIsValidatedAndDeliveredWithTemplates() {
        assertEquals("amount", SystemConfigService.shareFocus(null));
        assertEquals("amount", SystemConfigService.shareFocus("gold,light"));
        String base = "{\"version\":2,\"templates\":[{\"id\":\"light\",\"languages\":[\"*\"]}]";
        assertEquals("amount", SystemConfigService.shareFocus(base + "}"));
        for (String focus : new String[] {"amount", "rate"}) {
            String value = base + ",\"focus\":\"" + focus + "\"}";
            assertEquals(focus, SystemConfigService.shareFocus(value));
            SystemConfigService service = mock(SystemConfigService.class);
            when(service.getConfigValue("share.templates")).thenReturn(value);
            java.util.Map<?, ?> result = (java.util.Map<?, ?>) new CustomerServiceController(service, mock(com.gtcfesk.exchange.support.SupportSettings.class)).getShareTemplates("ja", true).getBody();
            assertEquals(focus, result.get("focus"));
            assertEquals(java.util.Collections.singletonList("light"), result.get("templates"));
        }
        for (String focus : new String[] {"null", "true", "1", "\"unknown\"", "[]"}) {
            String value = base + ",\"focus\":" + focus + "}";
            assertThrows(BusinessException.class, () -> SystemConfigService.shareTemplates(value));
            assertThrows(BusinessException.class, () -> new SystemConfigService().saveConfig("share.templates", value, "test"));
        }
    }

    @Test void rejectsMalformedAndUncoveredLanguageScopes() {
        for (String config : new String[] {
            "{}", "{\"version\":3,\"templates\":[]}",
            "{\"version\":2,\"templates\":[{\"id\":\"light\",\"languages\":[]}]}",
            "{\"version\":2,\"templates\":[{\"id\":\"light\",\"languages\":[\"ja\"]}]}",
            "{\"version\":2,\"templates\":[{\"id\":\"light\",\"languages\":[\"*\",\"ja\"]}]}",
            "{\"version\":2,\"templates\":[{\"id\":\"light\",\"languages\":[\"unknown\"]}]}",
            "{\"version\":2,\"templates\":[{\"id\":\"light\",\"languages\":[\"*\",\"*\"]}]}",
            "{\"version\":2,\"templates\":[{\"id\":\"light\",\"languages\":[\"*\"]},{\"id\":\"light\",\"languages\":[\"ja\"]}]}"
        }) assertThrows(BusinessException.class, () -> new SystemConfigService().saveConfig("share.templates", config, "test"));
    }
}
