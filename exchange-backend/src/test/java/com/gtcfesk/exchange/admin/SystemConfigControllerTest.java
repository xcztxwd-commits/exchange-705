package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SystemConfigControllerTest {
    @Test void shareMetadataReturnsTenantBrandWithoutExposingOtherSettings() {
        SystemConfigController controller = new SystemConfigController();
        SystemConfigService configs = mock(SystemConfigService.class);
        ReflectionTestUtils.setField(controller, "systemConfigService", configs);
        when(configs.getConfigValue("share.templates")).thenReturn("gold,light");
        when(configs.getConfigValue("site.name")).thenReturn("Tenant Exchange");
        Map<?, ?> result = (Map<?, ?>) controller.getConfig("share.templates").getBody();
        assertEquals("gold,light", result.get("value")); assertEquals("Tenant Exchange", result.get("brand"));
        assertFalse(((Map<?, ?>) controller.getConfig("system.timezone").getBody()).containsKey("brand"));
    }
    @Test void currencySaveRefreshesOnlyAfterCommit() {
        SystemConfigController controller = new SystemConfigController();
        SystemConfigService configs = mock(SystemConfigService.class);
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        ReflectionTestUtils.setField(controller, "systemConfigService", configs);
        ReflectionTestUtils.setField(controller, "market", market);
        Map<String,String> currency = new HashMap<>();
        currency.put("key", "market.conversion.currencies");
        currency.put("value", "USD,EUR");
        controller.saveConfig(currency);
        verify(market).requestSymbolRefresh();
        clearInvocations(market);

        TransactionSynchronizationManager.initSynchronization();
        try {
            controller.saveBatchConfig(Collections.singletonList(currency));
            verifyNoInteractions(market);
            for (TransactionSynchronization callback : TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
            verify(market).requestSymbolRefresh();
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
