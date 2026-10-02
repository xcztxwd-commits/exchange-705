package com.gtcfesk.exchange.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SupportLocaleTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SupportSettings settings = new SupportSettings(null, mapper);
    private SupportSettings.Settings localized() {
        SupportSettings.Settings s = new SupportSettings.Settings();
        s.fallbackLocale = "en";
        s.replies.put("en", reply("Welcome", "Offline"));
        s.replies.put("ja", reply("こんにちは", "ただいま不在です"));
        s.replies.put("zh-TW", reply("您好", "客服離線"));
        s.replies.put("zh-CN", reply("欢迎", "客服离线"));
        return s;
    }
    private SupportSettings.Reply reply(String welcome, String offline) {
        SupportSettings.Reply r = new SupportSettings.Reply(); r.welcome = welcome; r.offline = offline; return r;
    }
    @Test void roundTripAndCurrentPageLanguage() throws Exception {
        SupportSettings.Settings s = SupportSettings.parse(mapper.writeValueAsString(localized()));
        assertEquals("こんにちは", settings.welcome(s, "192.0.2.1", "ja"));
        assertEquals("ただいま不在です", settings.offline(s, "JA_jp"));
        assertEquals("您好", settings.welcome(s, "192.0.2.1", "zh-TW"));
        assertEquals("您好", settings.welcome(s, "192.0.2.1", "zh-Hant-HK"));
        assertEquals("欢迎", settings.welcome(s, "192.0.2.1", "zh-CN"));
        assertEquals("Welcome", settings.welcome(s, "192.0.2.1", "en-US"));
    }
    @Test void missingUnknownAndBlankFieldsFallbackIndependently() {
        SupportSettings.Settings s = localized();
        for (String locale : Arrays.asList(null, "", "fr", "unknown", "ja, en;q=0.9")) {
            assertEquals("Welcome", settings.welcome(s, "192.0.2.1", locale));
            assertEquals("Offline", settings.offline(s, locale));
        }
        s.replies.get("ja").offline = "  ";
        assertEquals("こんにちは", settings.welcome(s, "192.0.2.1", "ja"));
        assertEquals("Offline", settings.offline(s, "ja"));
        s.replies.get("en").offline = "";
        assertEquals(s.offline, settings.offline(s, "ja"));
        s.fallbackLocale = "fr";
        assertEquals(s.welcome, settings.welcome(s, "192.0.2.1", null));
    }
    @Test void legacySettingsAndIpOverridesRemainCompatible() {
        SupportSettings.Settings s = SupportSettings.parse("{\"welcome\":\"原欢迎语\",\"offline\":\"原离线提示\"}");
        assertEquals("原欢迎语", settings.welcome(s, "192.0.2.1", "ja"));
        assertEquals("原离线提示", settings.offline(s, null));
        s = localized();
        SupportSettings.Rule rule = new SupportSettings.Rule(); rule.cidr = "192.0.2.0/24"; rule.reply = "IP override"; s.rules.add(rule);
        assertEquals("IP override", settings.welcome(s, "192.0.2.1", "ja"));
        assertEquals("こんにちは", settings.welcome(s, "198.51.100.1", "ja"));
    }
    @Test void rejectInvalidLanguageMapsAndOversizedReplies() throws Exception {
        for (String raw : Arrays.asList("{\"replies\":null}", "{\"fallbackLocale\":null}",
                "{\"fallbackLocale\":\"xx\"}", "{\"replies\":{\"xx\":{}}}",
                "{\"replies\":{\"ja\":null}}", "{\"replies\":{\"ja\":{\"offline\":null}}}")) {
            assertThrows(BusinessException.class, () -> SupportSettings.parse(raw));
        }
        SupportSettings.Settings s = localized();
        s.replies.get("ja").welcome = String.join("", java.util.Collections.nCopies(2001, "a"));
        String raw = mapper.writeValueAsString(s);
        assertThrows(BusinessException.class, () -> SupportSettings.parse(raw));
    }
    @Test void httpForwardsExplicitPageLocaleAndSupportsOldClients() throws Exception {
        SupportService service = mock(SupportService.class);
        SupportSettings config = mock(SupportSettings.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserSupportController(service, config)).build();
        mvc.perform(post("/api/user/support/sessions").param("locale", "ja")).andExpect(status().isOk());
        verify(service).start("127.0.0.1", "ja");
        mvc.perform(post("/api/user/support/sessions")).andExpect(status().isOk());
        verify(service).start("127.0.0.1", null);
        mvc.perform(get("/api/user/support/config").param("locale", "zh-TW")).andExpect(status().isOk());
        verify(config).publicConfig("zh-TW");
    }
}
