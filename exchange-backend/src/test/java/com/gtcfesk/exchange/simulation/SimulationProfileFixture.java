package com.gtcfesk.exchange.simulation;

import java.util.*;

final class SimulationProfileFixture {
    static Map<String,Object> profile(long tenant, long id) {
        Map<String,Object> profile = new LinkedHashMap<>();
        for (String column : SimulationProfileBoundary.COLUMNS) profile.put(column, null);
        profile.put("tenant_id", tenant); profile.put("id", id);
        profile.put("email", "customer-" + id + "@example.test");
        profile.put("nickname", "Customer " + id); profile.put("remark", "正式账户备注");
        profile.put("user_type", "normal"); profile.put("status", "normal");
        profile.put("created_at", "2026-01-01 00:00:00.0");
        return profile;
    }
}
