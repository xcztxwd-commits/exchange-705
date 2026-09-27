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
    @Autowired
    private SystemConfigRepository systemConfigRepository;

    public List<SystemConfig> getAllConfigs() {
        return systemConfigRepository.findAll();
    }

    public String getConfigValue(String key) {
        Optional<SystemConfig> config = systemConfigRepository.findByConfigKey(key);
        return config.map(SystemConfig::getConfigValue).orElse(null);
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

    // Legacy CSV remains readable. Version 2 stores ordered language scopes atomically.
    public static List<String> shareTemplates(String value, String locale) {
        if (value == null) return SHARE_TEMPLATES;
        if (!value.trim().startsWith("{")) {
            List<String> selected = java.util.Arrays.asList(value.split(",", -1));
            if (!SHARE_TEMPLATES.containsAll(selected) || new java.util.HashSet<>(selected).size() != selected.size())
                throw invalidShareTemplates();
            return selected;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
            if (root.size() != 2 || !root.path("version").isIntegralNumber() || root.path("version").intValue() != 2
                    || !root.path("templates").isArray() || root.path("templates").size() == 0) throw invalidShareTemplates();
            java.util.Set<String> ids = new java.util.HashSet<>(), covered = new java.util.HashSet<>();
            List<String> selected = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode row : root.path("templates")) {
                String id = row.path("id").asText();
                com.fasterxml.jackson.databind.JsonNode languages = row.path("languages");
                if (!row.isObject() || row.size() != 2 || !SHARE_TEMPLATES.contains(id) || !ids.add(id)
                        || !languages.isArray() || languages.size() == 0) throw invalidShareTemplates();
                java.util.Set<String> scope = new java.util.HashSet<>();
                for (com.fasterxml.jackson.databind.JsonNode language : languages) {
                    String code = language.asText();
                    if (!language.isTextual() || !("*".equals(code) || SHARE_LANGUAGES.contains(code)) || !scope.add(code))
                        throw invalidShareTemplates();
                }
                if (scope.contains("*")) {
                    if (scope.size() != 1) throw invalidShareTemplates();
                    covered.addAll(SHARE_LANGUAGES);
                } else covered.addAll(scope);
                if (scope.contains("*") || scope.contains(shareLanguage(locale))) selected.add(id);
            }
            if (!covered.containsAll(SHARE_LANGUAGES))
                throw new com.gtcfesk.exchange.common.BusinessException("每种页面语言至少需要一款模板，请启用全语言通用模板或补齐语言配置");
            return selected;
        } catch (java.io.IOException invalid) { throw invalidShareTemplates(); }
    }

    private static com.gtcfesk.exchange.common.BusinessException invalidShareTemplates() {
        return new com.gtcfesk.exchange.common.BusinessException("分享模板配置无效：请检查模板编号、语言范围及重复项");
    }

    public void saveConfig(String key, String value, String description) {
        if (SHARE_TEMPLATES_KEY.equals(key)) {
            if (value == null) throw new com.gtcfesk.exchange.common.BusinessException("请选择分享模板");
            shareTemplates(value);
        }
        if ("market.conversion.cache-hours".equals(key)) {
            if (value == null || value.trim().isEmpty()) throw new com.gtcfesk.exchange.common.BusinessException("汇率更新间隔请输入 1–168 的整数小时");
            conversionCacheHours(value);
        }
        if ("market.conversion.currencies".equals(key)) {
            if (value == null) throw new com.gtcfesk.exchange.common.BusinessException("请选择缓存币种");
            value = String.join(",", conversionCurrencies(value));
        }
        Optional<SystemConfig> existing = systemConfigRepository.findByConfigKey(key);
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







