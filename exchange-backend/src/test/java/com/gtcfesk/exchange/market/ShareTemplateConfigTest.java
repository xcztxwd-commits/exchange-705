package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.user.CustomerServiceController;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class ShareTemplateConfigTest {
    static SystemConfigService fixture(){SystemConfigService s=new SystemConfigService();org.springframework.test.util.ReflectionTestUtils.setField(s,"tenantPolicy",mock(com.gtcfesk.exchange.control.TenantPolicyService.class));return s;}
    @Test void defaultsAndConfiguredOrderReachUsers() {
        assertEquals(16, SystemConfigService.shareTemplates(null).size());
        SystemConfigService service = mock(SystemConfigService.class);
        when(service.getConfigValue("share.templates")).thenReturn("gold,light");
        CustomerServiceController controller = new CustomerServiceController(service, mock(com.gtcfesk.exchange.support.SupportSettings.class));
        assertEquals(java.util.Arrays.asList("gold", "light"), controller.getShareTemplates("en", false).getBody());
        when(service.getConfigValue("site.name")).thenReturn("Tenant Exchange");
        assertEquals("Tenant Exchange", ((java.util.Map<?, ?>) controller.getShareTemplates("en", true).getBody()).get("brand"));
        when(service.getConfigValue("site.name")).thenReturn("Renamed Exchange");
        assertEquals("Renamed Exchange", ((java.util.Map<?, ?>) controller.getShareTemplates("ja", true).getBody()).get("brand"));
    }

    @Test void rejectsEmptyDuplicateAndUnknownTemplatesBeforeSaving() {
        SystemConfigService service = fixture();
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
            assertThrows(BusinessException.class, () -> fixture().saveConfig("share.templates", value, "test"));
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
        }) assertThrows(BusinessException.class, () -> fixture().saveConfig("share.templates", config, "test"));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode customConfig() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode config = mapper.createObjectNode(); config.put("version", 3);
        com.fasterxml.jackson.databind.node.ArrayNode templates = config.putArray("templates");
        com.fasterxml.jackson.databind.node.ObjectNode row = templates.addObject(); row.put("id", "custom-test"); row.put("name", "日本語テンプレート"); row.put("base", "light"); row.put("focus", "rate"); row.put("enabled", true); row.putArray("languages").add("ja");
        com.fasterxml.jackson.databind.node.ObjectNode design = row.putObject("design"); design.put("width", 1080); design.put("height", 1440); design.put("background", "#ffffff"); design.put("artwork", false);
        com.fasterxml.jackson.databind.node.ArrayNode boxes = design.putArray("boxes");
        for (String field : new String[]{"symbol", "leverage", "rate", "openPrice", "closePrice", "openTime", "closeTime", "userName", "userEmail"}) {
            com.fasterxml.jackson.databind.node.ObjectNode box = boxes.addObject(); box.put("field", field); box.put("x", 80); box.put("y", 40 + boxes.size() * 120); box.put("width", 920); box.put("height", 100); box.put("fontSize", 32); box.put("color", "#123456"); box.put("align", "left"); box.put("weight", 500); box.put("label", true); box.put("visible", true);
        }
        row = templates.addObject(); row.put("id", "dark"); row.put("name", "Fallback"); row.put("base", "dark"); row.put("focus", "amount"); row.put("enabled", true); row.putArray("languages").add("*");
        return config;
    }
    @Test void customLayoutsLanguageBindingFocusAndDisabledPersistenceReachUsers() {
        com.fasterxml.jackson.databind.node.ObjectNode config = customConfig(); String value = config.toString();
        assertEquals(java.util.Arrays.asList("custom-test", "dark"), SystemConfigService.shareTemplates(value, "ja"));
        assertEquals(java.util.Collections.singletonList("dark"), SystemConfigService.shareTemplates(value, "en"));
        SystemConfigService service = mock(SystemConfigService.class); when(service.getConfigValue("share.templates")).thenReturn(value);
        java.util.Map<?, ?> response = (java.util.Map<?, ?>) new CustomerServiceController(service, mock(com.gtcfesk.exchange.support.SupportSettings.class)).getShareTemplates("ja", true).getBody();
        java.util.List<?> definitions = (java.util.List<?>) response.get("definitions"); assertEquals(2, definitions.size());
        com.fasterxml.jackson.databind.JsonNode first = (com.fasterxml.jackson.databind.JsonNode) definitions.get(0); assertEquals("rate", first.path("focus").asText()); assertEquals("#123456", first.path("design").path("boxes").get(0).path("color").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) config.path("templates").get(0)).put("enabled", false);
        assertEquals(java.util.Collections.singletonList("dark"), SystemConfigService.shareTemplates(config.toString(), "ja"));
        assertEquals(1, SystemConfigService.shareTemplateDefinitions(config.toString(), "ja").size());
        assertTrue(config.toString().contains("custom-test")); // Stored draft is preserved, not served while disabled.
    }
    @Test void customLayoutsRejectUnknownFieldsCodeUrlsOutOfBoundsAndMissingLeverage() {
        for (String mutation : new String[]{"script", "url", "bounds", "missing", "duplicate", "focus", "oversized"}) {
            com.fasterxml.jackson.databind.node.ObjectNode config = customConfig();
            com.fasterxml.jackson.databind.node.ObjectNode row = (com.fasterxml.jackson.databind.node.ObjectNode) config.path("templates").get(0);
            com.fasterxml.jackson.databind.node.ArrayNode boxes = (com.fasterxml.jackson.databind.node.ArrayNode) row.path("design").path("boxes");
            com.fasterxml.jackson.databind.node.ObjectNode box = (com.fasterxml.jackson.databind.node.ObjectNode) boxes.get(0);
            if (mutation.equals("script")) box.put("field", "html");
            if (mutation.equals("url")) box.put("color", "url(https://invalid.example)");
            if (mutation.equals("bounds")) box.put("x", 5000);
            if (mutation.equals("missing")) ((com.fasterxml.jackson.databind.node.ObjectNode) boxes.get(1)).put("visible", false);
            if (mutation.equals("duplicate")) boxes.add(box.deepCopy());
            if (mutation.equals("focus")) row.put("focus", "unknown");
            if (mutation.equals("oversized")) ((com.fasterxml.jackson.databind.node.ObjectNode) row.path("design")).put("width", 10000);
            assertThrows(BusinessException.class, () -> SystemConfigService.shareTemplates(config.toString()), mutation);
        }
        assertThrows(BusinessException.class, () -> SystemConfigService.shareTemplates(new String(new char[60001]).replace('\0', 'x')));
    }
    private com.fasterxml.jackson.databind.node.ObjectNode decoration(String type) {
        com.fasterxml.jackson.databind.node.ObjectNode layer = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        layer.put("id", "layer-" + type.toLowerCase(java.util.Locale.ROOT)); layer.put("name", "素材"); layer.put("type", type); layer.put("x", 10); layer.put("y", 10); layer.put("width", 200); layer.put("height", 100);
        layer.put("fill", "#ffffff"); layer.put("stroke", "#123456"); layer.put("strokeWidth", 3); layer.put("radius", 24); layer.put("opacity", 0.5); layer.put("visible", true);
        if ("image".equals(type)) { layer.put("src", "/api/uploads/images/1/staff/9/fixture.png"); layer.put("fit", "contain"); }
        return layer;
    }
    @Test void shapeAndImageLayersPreserveOrderingAndRejectUnsafeConfigurations() {
        com.fasterxml.jackson.databind.node.ObjectNode config = customConfig(), design = (com.fasterxml.jackson.databind.node.ObjectNode) config.path("templates").get(0).path("design");
        com.fasterxml.jackson.databind.node.ArrayNode decorations = design.putArray("decorations"), layers = design.putArray("layers");
        for (String type : new String[]{"line", "ellipse", "rect", "roundRect", "image"}) { decorations.add(decoration(type)); layers.add("layer-" + type.toLowerCase(java.util.Locale.ROOT)); }
        for (com.fasterxml.jackson.databind.JsonNode box : design.path("boxes")) layers.add("box:" + box.path("field").asText());
        assertEquals(5, SystemConfigService.shareTemplateDefinitions(config.toString(), "ja").get(0).path("design").path("decorations").size());
        for (String mutation : new String[]{"order", "id", "geometry", "svg", "url", "user", "traversal", "format", "opacity", "capacity"}) {
            com.fasterxml.jackson.databind.node.ObjectNode bad = config.deepCopy(), layout = (com.fasterxml.jackson.databind.node.ObjectNode) bad.path("templates").get(0).path("design");
            com.fasterxml.jackson.databind.node.ArrayNode shapes = (com.fasterxml.jackson.databind.node.ArrayNode) layout.path("decorations");
            com.fasterxml.jackson.databind.node.ObjectNode shape = (com.fasterxml.jackson.databind.node.ObjectNode) shapes.get(0), image = (com.fasterxml.jackson.databind.node.ObjectNode) shapes.get(4);
            if (mutation.equals("order")) ((com.fasterxml.jackson.databind.node.ArrayNode) layout.path("layers")).set(0, new com.fasterxml.jackson.databind.node.TextNode("missing"));
            if (mutation.equals("id")) shape.put("id", "layer-image");
            if (mutation.equals("geometry")) shape.put("x", 1080);
            if (mutation.equals("svg")) shape.put("svg", "<script>alert(1)</script>");
            if (mutation.equals("url")) image.put("src", "https://example.invalid/asset.png");
            if (mutation.equals("user")) image.put("src", "/api/uploads/images/1/user/9/id.png");
            if (mutation.equals("traversal")) image.put("src", "/api/uploads/images/1/staff/9/../secret.png");
            if (mutation.equals("format")) image.put("src", "/api/uploads/images/1/staff/9/raw.svg");
            if (mutation.equals("opacity")) shape.put("opacity", 2);
            if (mutation.equals("capacity")) for (int i = 0; i < 41; i++) shapes.add(decoration("rect"));
            assertThrows(BusinessException.class, () -> SystemConfigService.shareTemplates(bad.toString()), mutation);
        }
    }
    @Test void tenantImageSharingNeverPublishesLibraryOnlyDisabledHiddenOrCrossTenantFiles() {
        SystemConfigService service = fixture();
        com.gtcfesk.exchange.repository.SystemConfigRepository repository = mock(com.gtcfesk.exchange.repository.SystemConfigRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "systemConfigRepository", repository);
        com.gtcfesk.exchange.control.TenantPolicyService policy = mock(com.gtcfesk.exchange.control.TenantPolicyService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tenantPolicy", policy);
        when(policy.effectiveConfig(anyString(), org.mockito.ArgumentMatchers.<String>any())).thenAnswer(call -> call.getArgument(1));
        com.fasterxml.jackson.databind.node.ObjectNode config = customConfig(), row = (com.fasterxml.jackson.databind.node.ObjectNode) config.path("templates").get(0), design = (com.fasterxml.jackson.databind.node.ObjectNode) row.path("design"), image = decoration("image");
        design.putArray("decorations").add(image);
        com.gtcfesk.exchange.entity.SystemConfig stored = new com.gtcfesk.exchange.entity.SystemConfig(); stored.setConfigValue(config.toString());
        when(repository.findByTenantIdAndConfigKey(1L, "share.templates")).thenReturn(java.util.Optional.of(stored));
        when(repository.findByTenantIdAndConfigKey(1L, "share.materials")).thenReturn(java.util.Optional.empty());
        String src = image.path("src").asText(); assertTrue(service.hasShareImage(src, true)); assertFalse(service.hasShareImage(src.replace("/1/", "/2/"), true));
        row.put("enabled", false); stored.setConfigValue(config.toString()); assertFalse(service.hasShareImage(src, true)); assertTrue(service.hasShareImage(src, false));
        row.put("enabled", true); image.put("visible", false); stored.setConfigValue(config.toString()); assertFalse(service.hasShareImage(src, true));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("10", null, java.util.Collections.singleton(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            assertDoesNotThrow(() -> service.requireShareImage(src)); // Already referenced by a tenant template.
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.requireShareImage(src.replace("fixture", "private")));
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.requireShareImage(src.replace("/1/", "/2/")));
            assertDoesNotThrow(() -> service.requireShareImage(src.replace("/staff/9/", "/staff/10/")));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        assertThrows(BusinessException.class, () -> SystemConfigService.shareMaterials("{}"));
    }
}
