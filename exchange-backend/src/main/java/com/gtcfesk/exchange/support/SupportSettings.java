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
    public static class Reply {
        public String welcome = "";
        public String offline = "";
    }
    private static final Set<String> LOCALES = new HashSet<>(Arrays.asList(
        "zh-CN", "zh-TW", "en", "fr", "de", "ru", "es", "pt", "it", "ar",
        "tr", "id", "my", "hi", "cs", "pl", "ja", "ko", "th", "vi"));
    public static class Settings {
        public String fallbackLocale = "";
        public Map<String, Reply> replies = new LinkedHashMap<>();
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
            if (s.replies == null || s.replies.size() > LOCALES.size() || s.fallbackLocale == null
                    || (!s.fallbackLocale.isEmpty() && !LOCALES.contains(s.fallbackLocale))) throw new IllegalArgumentException();
            for (Map.Entry<String, Reply> entry : s.replies.entrySet()) {
                if (!LOCALES.contains(entry.getKey()) || entry.getValue() == null) throw new IllegalArgumentException();
                text(entry.getValue().welcome, 2000); text(entry.getValue().offline, 2000);
            }
            for (Rule r : s.rules) {
                if (r == null || r.cidr == null || !r.cidr.matches("[0-9a-fA-F:./]{2,64}")) throw new IllegalArgumentException();
                new IpAddressMatcher(r.cidr); text(r.reply, 2000);
            }
            return s;
        } catch (Exception e) { throw new BusinessException("客服配置无效，请检查语言、回复长度、模式、容量、IP/CIDR 和提示音地址（配置总大小最多 60000 字节）"); }
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
    public String welcome(Settings s, String ip) { return welcome(s, ip, null); }
    public String welcome(Settings s, String ip, String locale) {
        // Explicit IP overrides retain their existing priority.
        for (Rule r : s.rules) if (new IpAddressMatcher(r.cidr).matches(ip)) return r.reply;
        return reply(s, locale, true);
    }
    public String offline(Settings s, String locale) { return reply(s, locale, false); }
    private String reply(Settings s, String locale, boolean welcome) {
        for (String key : Arrays.asList(normalizeLocale(locale), s.fallbackLocale)) {
            Reply r = s.replies.get(key);
            String text = r == null ? null : welcome ? r.welcome : r.offline;
            if (text != null && !text.trim().isEmpty()) return text;
        }
        return welcome ? s.welcome : s.offline;
    }
    private static String normalizeLocale(String locale) {
        if (locale == null || locale.length() > 64) return "";
        String tag = locale.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (!tag.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) return "";
        if (tag.equals("zh") || tag.startsWith("zh-")) {
            return tag.contains("-hant") || tag.equals("zh-tw") || tag.equals("zh-hk") || tag.equals("zh-mo") ? "zh-TW" : "zh-CN";
        }
        return tag.split("-", 2)[0];
    }
    public String externalLink() {
        String link = configs.getConfigValue("customer.service.link");
        if (link == null || link.trim().isEmpty()) return "";
        link = link.trim();
        if (!link.contains("://")) link = "https://" + link;
        try { URI uri = URI.create(link); return Arrays.asList("http", "https").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null ? link : ""; }
        catch (IllegalArgumentException e) { return ""; }
    }
    public Map<String,Object> publicConfig() { return publicConfig(null); }
    public Map<String,Object> publicConfig(String locale) {
        Settings s = get(); Map<String,Object> out = new LinkedHashMap<>();
        out.put("mode", s.mode); out.put("inboxEnabled", s.inboxEnabled); out.put("userSound", s.userSound);
        out.put("link", externalLink()); out.put("offline", offline(s, locale));
        return out;
    }
}
