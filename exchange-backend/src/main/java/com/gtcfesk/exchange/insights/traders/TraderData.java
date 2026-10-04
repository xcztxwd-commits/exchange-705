package com.gtcfesk.exchange.insights.traders;

import java.time.Instant;
import java.util.*;

/** Public curated metadata, never a platform user/account/order identity. */
public class TraderData {
    public String name, avatarUrl, bio, currency, sourceType="ADMIN_CURATED";
    public List<String> strategyTags=new ArrayList<>();
    public Instant statisticStart, statisticEnd;
    public String sourceNote, metricBasisNote, riskNote, authorizationNote;
    public String manualRoi, manualWinRate, manualMaxDrawdown;
    public int pointIntervalHours=24;

    public static class Edit { public Long rowVersion; public String reason; public TraderData data; }
    public static class Change { public Long rowVersion; public String reason, status; public Boolean disabled, recommended; public Integer sortOrder; }
    public static class EquityInput {
        public String pointAt, netAsset, cashFlow, currency, sourceNote;
    }
    public static class HistoryInput {
        public String recordKey, closedAt, symbol, direction, leverage, quantity, quantityUnit, pnl, pnlBasis="NOT_PROVIDED", fees, currency, sourceNote, evidenceNote;
    }
    public static class EquityEdit { public Long rowVersion; public String reason; public EquityInput data; }
    public static class HistoryEdit { public Long rowVersion; public String reason; public HistoryInput data; }
    public static class Import { public Long rowVersion; public String reason; public List<com.fasterxml.jackson.databind.JsonNode> rows; }
}
