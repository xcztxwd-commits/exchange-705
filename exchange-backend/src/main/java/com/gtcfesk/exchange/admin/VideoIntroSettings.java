package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/** One atomic configuration for language bindings and the fallback video. */
public final class VideoIntroSettings {
    public static final String KEY = "home.video.settings";
    public static final String URL_PREFIX = "/api/uploads/videos/";
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private VideoIntroSettings() { }

    public static boolean videoPath(String url, long tenant) {
        return url != null && url.matches(URL_PREFIX + tenant + "/staff/-?[0-9]{1,19}/"
                + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(mp4|webm)");
    }

    public static JsonNode parse(String value, long tenant) {
        try {
            if (value == null || value.length() > 16000) throw invalid();
            JsonNode root = MAPPER.readTree(value);
            if (root == null || !root.isObject() || root.size() != 2
                    || !root.path("defaultLocale").isTextual()
                    || !SystemConfigService.SHARE_LANGUAGES.contains(root.path("defaultLocale").asText())
                    || !root.path("videos").isObject()
                    || root.path("videos").size() > SystemConfigService.SHARE_LANGUAGES.size()) throw invalid();
            Iterator<Map.Entry<String, JsonNode>> entries = root.path("videos").fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                if (!SystemConfigService.SHARE_LANGUAGES.contains(entry.getKey()) || !entry.getValue().isTextual()
                        || !videoPath(entry.getValue().asText(), tenant)) throw invalid();
            }
            if (root.path("videos").size() > 0 && !root.path("videos").has(root.path("defaultLocale").asText()))
                throw new BusinessException("默认回退语言必须已上传视频");
            return root;
        } catch (IOException e) { throw invalid(); }
    }

    public static String language(JsonNode settings, String requested) {
        String tag = requested == null ? "" : requested.trim().replace('_', '-').toLowerCase(java.util.Locale.ROOT);
        String locale = tag.equals("zh") || tag.startsWith("zh-") ? "zh-TW" : tag.split("-", 2)[0];
        return settings.path("videos").has(locale) ? locale : settings.path("defaultLocale").asText();
    }

    public static boolean published(String value, String url, long tenant) {
        if (value == null || !videoPath(url, tenant)) return false;
        for (JsonNode video : parse(value, tenant).path("videos")) if (url.equals(video.asText())) return true;
        return false;
    }

    private static BusinessException invalid() { return new BusinessException("宣传视频语言配置无效，请使用本租户上传的视频"); }
}
