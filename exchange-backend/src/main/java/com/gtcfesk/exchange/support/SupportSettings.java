package com.gtcfesk.exchange.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;

@Service @RequiredArgsConstructor
public class SupportSettings {
    public static final String KEY = "support.settings";
    private final SystemConfigService configs;
    private final ObjectMapper mapper;

    public static class Rule {
        public String cidr = "";
        public String reply = "";
    }
    public static class Settings {
        public String mode = "external";
        public boolean inboxEnabled = false;
        public int capacity = 5;
        public String welcome = "您好，欢迎联系在线客服。请描述您需要帮助的问题。";
        public String offline = "客服暂未在线，您可以留言；上线后将按排队顺序接待。";
        public String adminSound = "/api/user/support/tones/arrival.wav";
        public String userSound = "/api/user/support/tones/reply.wav";
        public List<Rule> rules = new ArrayList<>();
    }
    public Settings get() {
        String raw = configs.getConfigValue(KEY);
        return raw == null ? new Settings() : parse(raw);
    }
    public static Settings parse(String raw) {
        try {
            if (raw == null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) throw new IllegalArgumentException();
            Settings s = new ObjectMapper().readValue(raw, Settings.class);
            if (s == null || !Arrays.asList("off", "external", "internal").contains(s.mode)
                    || s.capacity < 1 || s.capacity > 50 || s.rules == null || s.rules.size() > 50) throw new IllegalArgumentException();
            text(s.welcome, 2000); text(s.offline, 2000); sound(s.adminSound); sound(s.userSound);
            for (Rule r : s.rules) {
                if (r == null || r.cidr == null || !r.cidr.matches("[0-9a-fA-F:./]{2,64}")) throw new IllegalArgumentException();
                new IpAddressMatcher(r.cidr); text(r.reply, 2000);
            }
            return s;
        } catch (Exception e) { throw new BusinessException("客服配置无效，请检查模式、容量、IP/CIDR 和提示音地址"); }
    }
    private static void text(String s, int length) { if (s == null || s.length() > length) throw new IllegalArgumentException(); }
    private static void sound(String s) {
        text(s, 500);
        if (!s.isEmpty() && !s.matches("/api/(user/support/tones/[a-z]+\\.wav|uploads/audio/[a-zA-Z0-9_.-]+)")) throw new IllegalArgumentException();
    }
    public void save(Settings s) {
        try { String raw = mapper.writeValueAsString(s); parse(raw); configs.saveConfig(KEY, raw, "站内客服与站内信配置"); }
        catch (java.io.IOException e) { throw new IllegalArgumentException(e); }
    }
    public String welcome(Settings s, String ip) {
        for (Rule r : s.rules) if (new IpAddressMatcher(r.cidr).matches(ip)) return r.reply;
        return s.welcome;
    }
    public String externalLink() {
        String link = configs.getConfigValue("customer.service.link");
        if (link == null || link.trim().isEmpty()) return "";
        link = link.trim();
        if (!link.contains("://")) link = "https://" + link;
        try { URI uri = URI.create(link); return Arrays.asList("http", "https").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null ? link : ""; }
        catch (IllegalArgumentException e) { return ""; }
    }
    public Map<String,Object> publicConfig() {
        Settings s = get(); Map<String,Object> out = new LinkedHashMap<>();
        out.put("mode", s.mode); out.put("inboxEnabled", s.inboxEnabled); out.put("userSound", s.userSound);
        out.put("link", externalLink()); out.put("offline", s.offline);
        return out;
    }
}
