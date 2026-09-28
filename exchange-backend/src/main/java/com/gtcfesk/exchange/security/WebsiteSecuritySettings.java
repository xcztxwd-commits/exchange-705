package com.gtcfesk.exchange.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.gtcfesk.exchange.admin.SystemConfigService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Single database document: updates are atomic and visible to every backend instance. */
@Service
@RequiredArgsConstructor
public class WebsiteSecuritySettings {
    public static final String KEY = "security.registration.v1";
    private final SystemConfigService configs;
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    @Data
    public static class Policy {
        private Integer captchaIpPerMinute = 30;
        private Integer captchaSessionPerMinute = 10;
        private Integer captchaGlobalPerMinute = 600;
        private Integer registerIpPerMinute = 10;
        private Integer registerSessionPerMinute = 5;
        private Integer registerGlobalPerMinute = 120;
        public void validate() {
            range(captchaIpPerMinute, 1, 300); range(captchaSessionPerMinute, 1, 60);
            range(captchaGlobalPerMinute, 1, 10000); range(registerIpPerMinute, 1, 100);
            range(registerSessionPerMinute, 1, 30); range(registerGlobalPerMinute, 1, 3000);
        }
        private static void range(Integer value, int min, int max) {
            if (value == null || value < min || value > max) throw new IllegalArgumentException("Invalid security limit");
        }
    }

    public static Policy parse(String value) {
        try {
            Policy p = JSON.readValue(value, Policy.class);
            if (p == null) throw new IllegalArgumentException();
            p.validate();
            return p;
        } catch (Exception e) { throw new IllegalArgumentException("网站安全限流配置无效"); }
    }

    public Policy read() {
        try {
            String value = configs.getConfigValue(KEY);
            return value == null ? new Policy() : parse(value);
        } catch (Exception e) { throw SecurityFailure.unavailable(); }
    }

    public void save(Policy policy) {
        policy.validate();
        try { configs.saveConfig(KEY, JSON.writeValueAsString(policy), "网站安全：注册与验证码限流（每分钟）"); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("网站安全配置无效"); }
    }
}
