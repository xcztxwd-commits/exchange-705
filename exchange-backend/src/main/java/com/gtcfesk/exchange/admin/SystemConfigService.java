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

    public void saveConfig(String key, String value, String description) {
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







