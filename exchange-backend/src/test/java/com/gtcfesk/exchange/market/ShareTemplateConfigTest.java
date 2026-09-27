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
        assertEquals(java.util.Arrays.asList("gold", "light"), new CustomerServiceController(service).getShareTemplates("en").getBody());
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
        assertEquals(java.util.Arrays.asList("referenceWhite", "light"), new CustomerServiceController(service).getShareTemplates("ja").getBody());
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
