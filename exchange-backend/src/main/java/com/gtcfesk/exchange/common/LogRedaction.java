package com.gtcfesk.exchange.common;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
public final class LogRedaction {
    private static final ObjectMapper JSON = new ObjectMapper();
    private LogRedaction() { }
    private static boolean secret(String key) {
        String k = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return k.contains("password") || k.contains("passwd") || k.contains("secret") || k.contains("token")
                || k.contains("authorization") || k.contains("verifycode") || k.contains("verificationcode")
                || k.contains("apikey") || k.equals("code") || k.contains("credential");
    }
    private static void clean(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            boolean configSecret = secret(object.path("key").asText());
            for (String name : names) {
                if (secret(name) || (configSecret && name.equals("value"))) object.put(name, "[REDACTED]");
                else clean(object.get(name));
            }
        } else if (node.isArray()) for (JsonNode child : node) clean(child);
    }
    public static String sanitize(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !(node.isObject() || node.isArray())) return "[OMITTED]";
            clean(node);
            String result = JSON.writeValueAsString(node);
            return result.length() > 5000 ? result.substring(0, 5000) + "...(truncated)" : result;
        } catch (Exception e) { return "[OMITTED: invalid JSON]"; }
    }
}
