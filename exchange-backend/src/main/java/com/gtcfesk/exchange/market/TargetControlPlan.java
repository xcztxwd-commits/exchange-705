package com.gtcfesk.exchange.market;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The two durable plan versions share storage/read operations, not generation rules. */
interface TargetControlPlan {
    int version();
    int precision();
    Map<String, Object> snapshot();
    Map<String, Object> preview();
    Map<String, Object> summary();
    List<String> prices();
    BigDecimal price(long startedAt, long now);
    String checksum();
}
