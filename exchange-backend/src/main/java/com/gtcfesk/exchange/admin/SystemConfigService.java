package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.SystemConfig;
import com.gtcfesk.exchange.repository.SystemConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class SystemConfigService {
    public static final String VIDEO_INTRO_URL_KEY = "home.video.url";
    @Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;
    @Autowired private com.gtcfesk.exchange.tenant.TenantSecrets secrets;
    @Autowired
    private SystemConfigRepository systemConfigRepository;

    public List<SystemConfig> getAllConfigs() {
        java.util.List<SystemConfig> result=new java.util.ArrayList<>();
        for(SystemConfig stored:systemConfigRepository.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId())) {
            SystemConfig view=new SystemConfig(); view.setId(stored.getId()); view.setConfigKey(stored.getConfigKey()); view.setDescription(stored.getDescription()); view.setUpdatedAt(stored.getUpdatedAt()); view.setCreatedAt(stored.getCreatedAt());
            view.setConfigValue(com.gtcfesk.exchange.tenant.TenantSecrets.secret(stored.getConfigKey())?com.gtcfesk.exchange.tenant.TenantSecrets.MASK:tenantPolicy.effectiveConfig(stored.getConfigKey(),stored.getConfigValue()));result.add(view);
        } return result;
    }

    public com.gtcfesk.exchange.auth.RegistrationFields registrationFields() {
        return com.gtcfesk.exchange.auth.RegistrationFields.parse(getConfigValue(com.gtcfesk.exchange.auth.RegistrationFields.KEY));
    }

    public String getConfigValue(String key) {
        Optional<SystemConfig> config = systemConfigRepository.findByTenantIdAndConfigKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), key);
        String value=tenantPolicy.effectiveConfig(key,config.map(SystemConfig::getConfigValue).orElse(null));
        return com.gtcfesk.exchange.tenant.TenantSecrets.secret(key)?secrets.decrypt(key,value):value;
    }

    /** Same physical transaction as the caller's funding locks, never the one-second market-hours cache. */
    public String getCurrentConfigValue(String key) {
        tenantPolicy.lockCurrentTenant();long tenant=com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        List<?> policies=entityManager.createNativeQuery("SELECT policy_value,locked FROM tenant_policy WHERE tenant_id=?1 AND policy_key=?2 LOCK IN SHARE MODE")
                .setParameter(1,tenant).setParameter(2,"config."+key).getResultList();
        List<?> configs=entityManager.createNativeQuery("SELECT config_value FROM system_config WHERE tenant_id=?1 AND config_key=?2 LOCK IN SHARE MODE")
                .setParameter(1,tenant).setParameter(2,key).getResultList();
        if(policies.size()>1||configs.size()>1)throw new com.gtcfesk.exchange.common.BusinessException("当前配置不唯一");
        String value=configs.isEmpty()?null:(String)configs.get(0);
        if(!policies.isEmpty()){
            Object[] row=(Object[])policies.get(0);Object locked=row[1];
            if(Boolean.TRUE.equals(locked)||locked instanceof Number&&((Number)locked).intValue()==1)value=(String)row[0];
        }
        return com.gtcfesk.exchange.tenant.TenantSecrets.secret(key)?secrets.decrypt(key,value):value;
    }

    public static final String DEFAULT_CONVERSION_CURRENCIES = "USD,EUR,JPY,GBP,CNY,CHF,AUD,CAD,HKD,SGD";
    public static java.util.List<String> conversionCurrencies(String value) {
        if (value == null) value = DEFAULT_CONVERSION_CURRENCIES;
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        result.add("USD");
        for (String item : value.split(",", -1)) {
            String code = item.trim().toUpperCase(java.util.Locale.ROOT);
            try {
                if (!code.matches("[A-Z]{3}") || java.util.Currency.getInstance(code).getDefaultFractionDigits() < 0)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new com.gtcfesk.exchange.common.BusinessException("请选择有效的三位法定货币代码");
            }
            result.add(code);
        }
        if (result.size() > 30) throw new com.gtcfesk.exchange.common.BusinessException("最多缓存 30 种货币");
        return new java.util.ArrayList<>(result);
    }

    public static int conversionCacheHours(String value) {
        if (value == null) return 8;
        try {
            if (!value.matches("[0-9]{1,3}")) throw new NumberFormatException();
            int hours = Integer.parseInt(value);
            if (hours >= 1 && hours <= 168) return hours;
        } catch (NumberFormatException ignored) { }
        throw new com.gtcfesk.exchange.common.BusinessException("汇率更新间隔请输入 1–168 的整数小时");
    }

    public static final String SHARE_TEMPLATES_KEY = "share.templates";
    public static final String SHARE_MATERIALS_KEY = "share.materials";
    public static final List<String> SHARE_TEMPLATES = java.util.Arrays.asList("light", "dark", "chart", "gold", "globe", "architecture", "city", "referenceGold", "referenceWhite", "referenceTerminal", "launch", "aurora", "racing", "receipt", "journal", "voyage");

    public static final List<String> SHARE_LANGUAGES = java.util.Arrays.asList(
            "zh-TW", "en", "fr", "de", "ru", "es", "pt", "it", "ar", "tr", "id", "my", "hi", "cs", "pl", "ja", "ko", "th", "vi");

    public static String shareLanguage(String locale) {
        String tag = locale == null ? "en" : locale.trim().replace('_', '-').toLowerCase(java.util.Locale.ROOT);
        if (tag.equals("zh") || tag.startsWith("zh-")) return "zh-TW";
        String language = tag.split("-", 2)[0];
        return SHARE_LANGUAGES.contains(language) ? language : "en";
    }

    public static List<String> shareTemplates(String value) { return shareTemplates(value, "en"); }

    // CSV / v2 remain readable. V3 adds per-template focus and bounded fixed-field layouts.
    public static List<String> shareTemplates(String value, String locale) {
        if (value == null) return SHARE_TEMPLATES;
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) throw invalidShareTemplates();
        if (!value.trim().startsWith("{")) {
            List<String> selected = java.util.Arrays.asList(value.split(",", -1));
            if (!SHARE_TEMPLATES.containsAll(selected) || new java.util.HashSet<>(selected).size() != selected.size())
                throw invalidShareTemplates();
            return selected;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
            int version = root.path("version").asInt();
            if (!root.isObject() || root.size() != (root.has("focus") ? 3 : 2) || !root.path("version").isIntegralNumber() || (version != 2 && version != 3)
                    || !root.path("templates").isArray() || root.path("templates").size() == 0 || root.path("templates").size() > 32) throw invalidShareTemplates();
            if (root.has("focus") && (!root.path("focus").isTextual() || !java.util.Arrays.asList("amount", "rate").contains(root.path("focus").asText()))) throw invalidShareTemplates();
            java.util.Set<String> ids = new java.util.HashSet<>(), covered = new java.util.HashSet<>();
            List<String> selected = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode row : root.path("templates")) {
                String id = row.path("id").asText();
                com.fasterxml.jackson.databind.JsonNode languages = row.path("languages");
                boolean builtin = SHARE_TEMPLATES.contains(id);
                if (!row.isObject() || !row.path("id").isTextual() || (!builtin && (version != 3 || !id.matches("custom-[a-z0-9-]{1,48}"))) || !ids.add(id)
                        || (version == 2 && row.size() != 2)
                        || !languages.isArray() || languages.size() == 0) throw invalidShareTemplates();
                if (version == 3) {
                    if (!ShareTemplateDesignValidator.keys(row, "id", "name", "base", "languages", "focus", "enabled", "design") || row.size() != 5 + (row.has("design") ? 1 : 0) + (row.has("enabled") ? 1 : 0)
                            || !row.path("name").isTextual() || row.path("name").asText().trim().isEmpty() || row.path("name").asText().length() > 80
                            || !row.path("base").isTextual() || !SHARE_TEMPLATES.contains(row.path("base").asText())
                            || !row.path("focus").isTextual() || !java.util.Arrays.asList("amount", "rate").contains(row.path("focus").asText())
                            || (row.has("enabled") && !row.path("enabled").isBoolean()) || (!builtin && !row.has("design"))) throw invalidShareTemplates();
                    if (row.has("design")) ShareTemplateDesignValidator.validate(row.get("design"));
                }
                java.util.Set<String> scope = new java.util.HashSet<>();
                for (com.fasterxml.jackson.databind.JsonNode language : languages) {
                    String code = language.asText();
                    if (!language.isTextual() || !("*".equals(code) || SHARE_LANGUAGES.contains(code)) || !scope.add(code))
                        throw invalidShareTemplates();
                }
                boolean enabled = version == 2 || !row.has("enabled") || row.path("enabled").asBoolean();
                if (scope.contains("*")) {
                    if (scope.size() != 1) throw invalidShareTemplates();
                    if (enabled) covered.addAll(SHARE_LANGUAGES);
                } else if (enabled) covered.addAll(scope);
                if (enabled && (scope.contains("*") || scope.contains(shareLanguage(locale)))) selected.add(id);
            }
            if (!covered.containsAll(SHARE_LANGUAGES))
                throw new com.gtcfesk.exchange.common.BusinessException("每种页面语言至少需要一款模板，请启用全语言通用模板或补齐语言配置");
            return selected;
        } catch (java.io.IOException invalid) { throw invalidShareTemplates(); }
    }

    public static String shareFocus(String value) {
        shareTemplates(value); // Validate the same atomic template configuration before reading it.
        if (value == null || !value.trim().startsWith("{")) return "amount";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value).path("focus").asText("amount");
        } catch (java.io.IOException invalid) { throw invalidShareTemplates(); }
    }

    public static java.util.List<com.fasterxml.jackson.databind.JsonNode> shareTemplateDefinitions(String value, String locale) {
        List<String> selected = shareTemplates(value, locale);
        java.util.List<com.fasterxml.jackson.databind.JsonNode> result = new java.util.ArrayList<>();
        if (value == null || !value.trim().startsWith("{")) return result;
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
            if (root.path("version").asInt() == 3) for (com.fasterxml.jackson.databind.JsonNode row : root.path("templates"))
                if (selected.contains(row.path("id").asText())) result.add(row);
            return result;
        } catch (java.io.IOException invalid) { throw invalidShareTemplates(); }
    }

    private static com.gtcfesk.exchange.common.BusinessException invalidShareTemplates() {
        return new com.gtcfesk.exchange.common.BusinessException("分享模板配置无效：请检查模板编号、语言范围及重复项");
    }

    public static com.fasterxml.jackson.databind.node.ObjectNode shareMaterials(String value) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        if (value == null) { com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode(); root.put("version", 1); root.putArray("materials"); return root; }
        try {
            if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) throw new IllegalArgumentException();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(value);
            if (!ShareTemplateDesignValidator.keys(root, "version", "materials") || root.size() != 2 || !root.path("version").isIntegralNumber() || root.path("version").asInt() != 1
                    || !root.path("materials").isArray() || root.path("materials").size() > 500) throw new IllegalArgumentException();
            java.util.Set<String> ids = new java.util.HashSet<>(); int active = 0;
            for (com.fasterxml.jackson.databind.JsonNode item : root.path("materials")) {
                if (!ShareTemplateDesignValidator.keys(item, "id", "name", "layer", "deleted") || item.size() != 4 || !item.path("deleted").isBoolean()
                        || !item.path("id").isTextual() || !item.path("id").asText().matches("material-[a-z0-9-]{1,48}") || !ids.add(item.path("id").asText())
                        || !item.path("name").isTextual() || item.path("name").asText().trim().isEmpty() || item.path("name").asText().length() > 80) throw new IllegalArgumentException();
                ShareTemplateDesignValidator.validateDecoration(item.path("layer"), 2160, 2160);
                if (!item.path("deleted").asBoolean() && ++active > 100) throw new IllegalArgumentException();
            }
            return (com.fasterxml.jackson.databind.node.ObjectNode) root;
        } catch (java.io.IOException | IllegalArgumentException e) { throw new com.gtcfesk.exchange.common.BusinessException("素材库配置无效或容量已满（最多 100 个可用素材）"); }
    }

    public boolean hasShareImage(String src, boolean publishedOnly) {
        if (!ShareTemplateDesignValidator.imagePath(src) || !src.startsWith("/api/uploads/images/" + com.gtcfesk.exchange.tenant.TenantContext.requireTenantId() + "/staff/")) return false;
        String value = getConfigValue(SHARE_TEMPLATES_KEY);
        try {
            if (value != null && value.trim().startsWith("{")) {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
                for (com.fasterxml.jackson.databind.JsonNode row : root.path("templates")) {
                    if (publishedOnly && !row.path("enabled").asBoolean(true)) continue;
                    for (com.fasterxml.jackson.databind.JsonNode layer : row.path("design").path("decorations"))
                        if ("image".equals(layer.path("type").asText()) && src.equals(layer.path("src").asText()) && (!publishedOnly || layer.path("visible").asBoolean())) return true;
                }
            }
            if (!publishedOnly) for (com.fasterxml.jackson.databind.JsonNode item : shareMaterials(getConfigValue(SHARE_MATERIALS_KEY)).path("materials"))
                if ("image".equals(item.path("layer").path("type").asText()) && src.equals(item.path("layer").path("src").asText())) return true;
        } catch (java.io.IOException e) { return false; }
        return false;
    }

    public void requireShareImage(String src) {
        String own = "/api/uploads/images/" + com.gtcfesk.exchange.tenant.TenantFiles.ownerPath() + "/";
        if (!ShareTemplateDesignValidator.imagePath(src) || !src.startsWith("/api/uploads/images/" + com.gtcfesk.exchange.tenant.TenantContext.requireTenantId() + "/staff/")
                || (!src.startsWith(own) && !hasShareImage(src, false))) throw new org.springframework.security.access.AccessDeniedException("只能使用自己上传或已入库的本租户素材图片");
    }

    private void requireShareImages(String value, boolean library) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
            for (com.fasterxml.jackson.databind.JsonNode row : root.path(library ? "materials" : "templates")) {
                Iterable<com.fasterxml.jackson.databind.JsonNode> layers = library ? java.util.Collections.singletonList(row.path("layer")) : row.path("design").path("decorations");
                for (com.fasterxml.jackson.databind.JsonNode layer : layers) if ("image".equals(layer.path("type").asText())) requireShareImage(layer.path("src").asText());
            }
        } catch (java.io.IOException e) { throw invalidShareTemplates(); }
    }

    @org.springframework.transaction.annotation.Transactional
    public void saveConfig(String key, String value, String description) {
        tenantPolicy.lockCurrentTenantForWrite();
        if (VIDEO_INTRO_URL_KEY.equals(key)) {
            value = value == null ? "" : value.trim();
            if (!value.isEmpty()) {
                try {
                    java.net.URI url = new java.net.URI(value);
                    if (!("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                            || url.getHost() == null || url.getUserInfo() != null || url.getPort() == 0 || url.getPort() > 65535)
                        throw new java.net.URISyntaxException(value, "Invalid video URL");
                } catch (java.net.URISyntaxException invalid) {
                    throw new com.gtcfesk.exchange.common.BusinessException("视频地址请输入有效的 HTTP/HTTPS URL，且不能包含用户名或密码");
                }
            }
        }
        tenantPolicy.requireConfigChange(key,value);
        if (VideoIntroSettings.KEY.equals(key)) VideoIntroSettings.parse(value, com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        if (com.gtcfesk.exchange.market.MarketHoursConfig.KEY.equals(key)) com.gtcfesk.exchange.market.MarketHoursConfig.parse(value);
        if (com.gtcfesk.exchange.market.MarketDepthService.ENABLED_KEY.equals(key) && !"true".equals(value) && !"false".equals(value))
            throw new com.gtcfesk.exchange.common.BusinessException("深度开关必须为 true 或 false");
        if ("ui.advanced.enabled".equals(key) && !"true".equals(value) && !"false".equals(value))
            throw new com.gtcfesk.exchange.common.BusinessException("高级版入口开关必须为 true 或 false");
        if (com.gtcfesk.exchange.user.KycIdentityService.TRADE_KYC_KEY.equals(key) && !"true".equals(value) && !"false".equals(value))
            throw new com.gtcfesk.exchange.common.BusinessException("未实名不可交易开关必须为 true 或 false");
        if(com.gtcfesk.exchange.tenant.TenantSecrets.secret(key)){
            if(com.gtcfesk.exchange.tenant.TenantSecrets.MASK.equals(value))return;
            value=secrets.encrypt(key,value);
        }
        if (com.gtcfesk.exchange.auth.RegistrationFields.KEY.equals(key)) com.gtcfesk.exchange.auth.RegistrationFields.parse(value);
        if ("support.settings".equals(key)) com.gtcfesk.exchange.support.SupportSettings.parse(value);
        if (com.gtcfesk.exchange.security.WebsiteSecuritySettings.KEY.equals(key)) com.gtcfesk.exchange.security.WebsiteSecuritySettings.parse(value);
        if (SHARE_TEMPLATES_KEY.equals(key)) {
            if (value == null) throw new com.gtcfesk.exchange.common.BusinessException("请选择分享模板");
            shareTemplates(value);
            if (value.trim().startsWith("{")) requireShareImages(value, false);
        }
        if (SHARE_MATERIALS_KEY.equals(key)) { shareMaterials(value); if (value == null) throw invalidShareTemplates(); requireShareImages(value, true); }
        if ("market.conversion.cache-hours".equals(key)) {
            if (value == null || value.trim().isEmpty()) throw new com.gtcfesk.exchange.common.BusinessException("汇率更新间隔请输入 1–168 的整数小时");
            conversionCacheHours(value);
        }
        if ("market.conversion.currencies".equals(key)) {
            if (value == null) throw new com.gtcfesk.exchange.common.BusinessException("请选择缓存币种");
            value = String.join(",", conversionCurrencies(value));
        }
        Optional<SystemConfig> existing = systemConfigRepository.findByTenantIdAndConfigKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), key);
        SystemConfig config;
        if (existing.isPresent()) {
            config = existing.get();
            config.setConfigValue(value);
            config.setUpdatedAt(LocalDateTime.now());
            if (description != null) {
                config.setDescription(description);
            }
        } else {
            config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
        }
        systemConfigRepository.save(config);
    }
}







