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
        assertEquals(java.util.Arrays.asList("gold", "light"), new CustomerServiceController(service).getShareTemplates().getBody());
    }

    @Test void rejectsEmptyDuplicateAndUnknownTemplatesBeforeSaving() {
        SystemConfigService service = new SystemConfigService();
        for (String value : new String[] { null, "", "light,light", "unknown", "light,", " light" }) {
            assertThrows(BusinessException.class, () -> service.saveConfig("share.templates", value, "test"));
        }
    }
}
