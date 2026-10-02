package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.control.TenantPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UiEditionSettingsTest {
    @Test void entryDefaultsOnAndFollowsSavedSwitch() {
        SystemConfigService configs = mock(SystemConfigService.class);
        CustomerServiceController controller = new CustomerServiceController(configs, null);
        for (String value : new String[]{null, "true", "false"}) {
            when(configs.getConfigValue("ui.advanced.enabled")).thenReturn(value);
            Map<?, ?> body = (Map<?, ?>) controller.getUiEdition().getBody();
            assertEquals(!"false".equals(value), body.get("advancedEnabled"));
            assertEquals(1, body.size());
        }
    }
    @Test void invalidSwitchValuesCannotBeSaved() {
        SystemConfigService configs = new SystemConfigService();
        ReflectionTestUtils.setField(configs, "tenantPolicy", mock(TenantPolicyService.class));
        for (String value : new String[]{null, "", "0", "FALSE", "yes"})
            assertThrows(BusinessException.class, () -> configs.saveConfig("ui.advanced.enabled", value, null));
    }
}
