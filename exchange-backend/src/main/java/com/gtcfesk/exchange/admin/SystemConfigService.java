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

    public void saveConfig(String key, String value, String description) {
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







