package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gtcfesk.exchange.common.BusinessException;
import java.util.*;

/** Bounded fixed data fields, passive vector geometry and explicitly shared tenant image files. */
public final class ShareTemplateDesignValidator {
    private ShareTemplateDesignValidator() { }
    private static final List<String> FIELDS = Arrays.asList("brand", "symbol", "direction", "leverage", "amount", "rate", "openPrice", "closePrice", "openTime", "closeTime", "userName", "userEmail");
    private static final List<String> REQUIRED = Arrays.asList("symbol", "leverage", "rate", "openPrice", "closePrice", "openTime", "closeTime");
    public static void validate(JsonNode design) {
        if (!keys(design, "width", "height", "background", "artwork", "boxes", "decorations", "layers") || design.size() != 5 + (design.has("decorations") ? 1 : 0) + (design.has("layers") ? 1 : 0)
                || !design.path("width").isIntegralNumber() || !design.path("height").isIntegralNumber()
                || !number(design.path("width"), 320, 2160) || !number(design.path("height"), 320, 2160)
                || !color(design.path("background")) || !design.path("artwork").isBoolean()
                || !design.path("boxes").isArray() || design.path("boxes").size() > FIELDS.size()) invalid();
        double width = design.path("width").asDouble(), height = design.path("height").asDouble();
        Set<String> used = new HashSet<>(), visible = new HashSet<>();
        for (JsonNode box : design.path("boxes")) {
            String field = box.path("field").asText();
            if (!keys(box, "field", "x", "y", "width", "height", "fontSize", "color", "align", "weight", "label", "visible") || box.size() != 11
                    || !box.path("field").isTextual() || !FIELDS.contains(field) || !used.add(field)
                    || !number(box.path("x"), 0, width) || !number(box.path("y"), 0, height)
                    || !number(box.path("width"), 20, width) || !number(box.path("height"), 20, height)
                    || box.path("x").asDouble() + box.path("width").asDouble() > width || box.path("y").asDouble() + box.path("height").asDouble() > height
                    || !number(box.path("fontSize"), 8, 200) || !color(box.path("color"))
                    || !box.path("align").isTextual() || !Arrays.asList("left", "center", "right").contains(box.path("align").asText())
                    || !box.path("weight").isIntegralNumber() || !Arrays.asList(400, 500, 600, 700, 800).contains(box.path("weight").asInt())
                    || !box.path("label").isBoolean() || !box.path("visible").isBoolean()) invalid();
            if (box.path("visible").asBoolean()) visible.add(field);
        }
        if (!visible.containsAll(REQUIRED)) invalid();
        Set<String> layerKeys = new HashSet<>(); for (String field : used) layerKeys.add("box:" + field);
        if (design.has("decorations")) {
            if (!design.path("decorations").isArray() || design.path("decorations").size() > 40) invalid();
            int images = 0;
            for (JsonNode layer : design.path("decorations")) {
                validateDecoration(layer, width, height);
                if (!layerKeys.add(layer.path("id").asText())) invalid();
                if ("image".equals(layer.path("type").asText()) && ++images > 16) invalid();
            }
        }
        if (design.has("layers")) {
            if (!design.path("layers").isArray() || design.path("layers").size() != layerKeys.size()) invalid();
            for (JsonNode key : design.path("layers")) if (!key.isTextual() || !layerKeys.remove(key.asText())) invalid();
            if (!layerKeys.isEmpty()) invalid();
        }
    }
    public static void validateDecoration(JsonNode layer, double width, double height) {
        boolean image = "image".equals(layer.path("type").asText());
        if (!keys(layer, "id", "name", "type", "x", "y", "width", "height", "fill", "stroke", "strokeWidth", "radius", "opacity", "visible", "src", "fit") || layer.size() != (image ? 15 : 13)
                || !layer.path("id").isTextual() || !layer.path("id").asText().matches("layer-[a-z0-9-]{1,48}")
                || !layer.path("name").isTextual() || layer.path("name").asText().trim().isEmpty() || layer.path("name").asText().length() > 80
                || !layer.path("type").isTextual() || !Arrays.asList("line", "ellipse", "rect", "roundRect", "image").contains(layer.path("type").asText())
                || !number(layer.path("x"), 0, width) || !number(layer.path("y"), 0, height)
                || !number(layer.path("width"), 2, width) || !number(layer.path("height"), 2, height)
                || layer.path("x").asDouble() + layer.path("width").asDouble() > width || layer.path("y").asDouble() + layer.path("height").asDouble() > height
                || !(color(layer.path("fill")) || "none".equals(layer.path("fill").asText())) || !(color(layer.path("stroke")) || "none".equals(layer.path("stroke").asText()))
                || !number(layer.path("strokeWidth"), 0, 100) || !number(layer.path("radius"), 0, 1080) || !number(layer.path("opacity"), 0, 1) || !layer.path("visible").isBoolean()) invalid();
        if (image) {
            if (!layer.path("src").isTextual() || !imagePath(layer.path("src").asText()) || !layer.path("fit").isTextual() || !Arrays.asList("contain", "cover", "stretch").contains(layer.path("fit").asText())) invalid();
        } else if (layer.has("src") || layer.has("fit")) invalid();
    }
    public static boolean imagePath(String src) {
        return src != null && src.matches("(?i)/api/uploads/images/[1-9][0-9]{0,18}/staff/(?:agent-)?-?[0-9]{1,19}/[a-zA-Z0-9_-]{1,100}\\.(png|jpe?g|gif|webp)");
    }
    public static boolean keys(JsonNode node, String... allowed) {
        if (node == null || !node.isObject()) return false;
        List<String> keys = Arrays.asList(allowed); Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) if (!keys.contains(fields.next())) return false;
        return true;
    }
    private static boolean number(JsonNode value, double min, double max) {
        double number = value.asDouble(); return value.isNumber() && Double.isFinite(number) && number >= min && number <= max;
    }
    private static boolean color(JsonNode value) { return value.isTextual() && value.asText().matches("#[0-9a-fA-F]{6}"); }
    private static void invalid() { throw new BusinessException("模板排版无效：须保留杠杆及交易字段；形状、图片地址、图层顺序、坐标和颜色必须有效"); }
}
