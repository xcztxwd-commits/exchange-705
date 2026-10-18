package com.gtcfesk.exchange.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.user.CustomerServiceController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SupportExternalLinkTest {
    private static final String LINK = "https://linkschatin.com/agent/chat.html?key=regression-test";
    // A browser link must not depend on the backend's DNS or outbound policy.
    private MockMvc endpoints(String link, String mode, boolean enabled) {
        SystemConfigService configs = mock(SystemConfigService.class);
        TenantPolicyService policy = mock(TenantPolicyService.class);
        when(configs.getConfigValue(SupportSettings.KEY)).thenReturn("{\"mode\":\"" + mode + "\"}");
        when(configs.getConfigValue("customer.service.link")).thenReturn(link);
        when(policy.featureEnabled("external_support")).thenReturn(enabled);
        SupportSettings settings = new SupportSettings(configs, new ObjectMapper());
        ReflectionTestUtils.setField(settings, "policy", policy);
        return MockMvcBuilders.standaloneSetup(new UserSupportController(mock(SupportService.class), settings),
            new CustomerServiceController(configs, settings)).build();
    }
    private void assertLink(MockMvc mvc, String link, boolean available) throws Exception {
        mvc.perform(get("/api/user/support/config")).andExpect(status().isOk()).andExpect(jsonPath("$.link").value(link));
        mvc.perform(get("/api/user/customer-service/link")).andExpect(status().isOk())
            .andExpect(jsonPath("$.link").value(link)).andExpect(jsonPath("$.available").value(available));
    }
    @Test void savedPublicLinksPublishWithoutAnOperatorAllowlist() throws Exception {
        assertLink(endpoints(LINK, "external", true), LINK, true);
        String other = "https://other.example.com:8443/chat?key=test";
        assertLink(endpoints(other, "external", true), other, true);
    }
    @Test void configuredLinksPublishWithoutUrlOrDnsValidation() throws Exception {
        for (String link : new String[]{"http://linkschatin.com/chat", "https://user@linkschatin.com/chat",
                "https://127.0.0.1/chat", "/chat", "linkschatin.com/chat", LINK + "#chat",
                "custom://support/chat", "https://support.example/chat with spaces", "https://[", "  "})
            assertLink(endpoints(link, "external", true), link, true);
    }
    @Test void missingLinksRemainUnavailable() throws Exception {
        for (String link : new String[]{null, ""})
            assertLink(endpoints(link, "external", true), "", false);
    }
    @Test void disabledChannelOrFeatureStillSuppressesExternalLinks() throws Exception {
        assertLink(endpoints(LINK, "off", true), "", false);
        assertLink(endpoints(LINK, "external", false), "", false);
    }
}
