package com.gtcfesk.exchange.admin;

import java.math.BigInteger;
import java.util.*;

/** Replacement payloads: missing IDs are not an instruction to revoke everything. */
final class PermissionGrantInput {
    private PermissionGrantInput() {}

    static List<Long> menuIds(Map<String, Object> request) {
        Object raw = request.get("menuIds");
        if (!(raw instanceof List)) throw new IllegalArgumentException("menuIds 必须为整数数组；清空请显式传 []");
        Set<Long> ids = new LinkedHashSet<>();
        for (Object value : (List<?>) raw) {
            // JSON integer tokens only: never truncate a decimal or round a floating-point ID.
            if (!(value instanceof Integer || value instanceof Long || value instanceof BigInteger))
                throw new IllegalArgumentException("菜单 ID 必须为正整数数值");
            try {
                long id = new BigInteger(value.toString()).longValueExact();
                if (id <= 0) throw new ArithmeticException();
                ids.add(id);
            } catch (ArithmeticException e) { throw new IllegalArgumentException("菜单 ID 超出正整数范围"); }
        }
        return new ArrayList<>(ids);
    }

    static Map<Long, List<String>> actions(Map<String, Object> request) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        if (!request.containsKey("actions")) return result; // Explicit replacement: omitted actions means none.
        Object raw = request.get("actions");
        if (!(raw instanceof Map)) throw new IllegalArgumentException("actions 必须为对象");
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String) || !((String) key).matches("[1-9][0-9]*"))
                throw new IllegalArgumentException("操作所属菜单 ID 无效");
            long id;
            try { id = Long.parseLong((String) key); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("操作所属菜单 ID 超出范围"); }
            if (!(entry.getValue() instanceof List)) throw new IllegalArgumentException("操作权限必须为字符串数组");
            Set<String> codes = new LinkedHashSet<>();
            for (Object code : (List<?>) entry.getValue()) {
                if (!(code instanceof String) || ((String) code).isEmpty()) throw new IllegalArgumentException("操作权限必须为非空字符串");
                codes.add((String) code);
            }
            result.put(id, new ArrayList<>(codes));
        }
        return result;
    }
}
