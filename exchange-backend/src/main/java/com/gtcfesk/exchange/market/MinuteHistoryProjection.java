package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pure, bounded assembly of already completed facts; not a source reader or an engine worker. */
public final class MinuteHistoryProjection {
    public static final int MAX_MINUTES = 500;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private MinuteHistoryProjection() {}

    public static final class SourceMinute {
        public final Map<String,Object> body;
        public final long receivedAt;
        public SourceMinute(Map<String,Object> body, long receivedAt) { this.body = body; this.receivedAt = receivedAt; }
    }
    /** Body must come from the existing visibleMixed reconstruction, with its actual included-event cutoff. */
    public static final class VisibleMinute {
        public final Map<String,Object> body;
        public final long lastIncludedAt;
        public VisibleMinute(Map<String,Object> body, long lastIncludedAt) { this.body = body; this.lastIncludedAt = lastIncludedAt; }
    }
    /** The caller must atomically obtain these committed facts under the eventual S2 contract. */
    public static final class CompletedFacts {
        public final long symbol, generation, version, expectedWatermark, watermark, fromMinute, toMinute, stopAt, receivedCutoff;
        public final long sourceInputRevision;
        public final List<SourceMinute> sourceMinutes;
        public final List<VisibleMinute> visibleMinutes;
        public CompletedFacts(long symbol, long generation, long version, long expectedWatermark, long watermark,
                long fromMinute, long toMinute, long stopAt, long receivedCutoff,
                List<SourceMinute> sourceMinutes, List<VisibleMinute> visibleMinutes) {
            this(symbol,generation,version,expectedWatermark,watermark,fromMinute,toMinute,stopAt,receivedCutoff,sourceMinutes,visibleMinutes,0);
        }
        public CompletedFacts(long symbol,long generation,long version,long expectedWatermark,long watermark,
                long fromMinute,long toMinute,long stopAt,long receivedCutoff,List<SourceMinute> sourceMinutes,
                List<VisibleMinute> visibleMinutes,long sourceInputRevision) {
            this.sourceInputRevision=sourceInputRevision;
            this.symbol = symbol; this.generation = generation; this.version = version;
            this.expectedWatermark = expectedWatermark; this.watermark = watermark;
            this.fromMinute = fromMinute; this.toMinute = toMinute; this.stopAt = stopAt; this.receivedCutoff = receivedCutoff;
            this.sourceMinutes = sourceMinutes; this.visibleMinutes = visibleMinutes;
        }
    }
    public static final class Minute {
        public final long minuteAt;
        public final String body;
        public final boolean mixed;
        private Minute(long minuteAt, String body, boolean mixed) { this.minuteAt = minuteAt; this.body = body; this.mixed = mixed; }
    }
    public static final class Block {
        public final long symbol, generation, version, expectedWatermark, watermark, fromMinute, toMinute, stopAt, receivedCutoff;
        public final long sourceInputRevision;
        public final List<Minute> minutes;
        public final String hash;
        private Block(CompletedFacts facts, List<Minute> minutes, String hash) {
            sourceInputRevision=facts.sourceInputRevision;
            symbol = facts.symbol; generation = facts.generation; version = facts.version;
            expectedWatermark = facts.expectedWatermark; watermark = facts.watermark;
            fromMinute = facts.fromMinute; toMinute = facts.toMinute; stopAt = facts.stopAt; receivedCutoff = facts.receivedCutoff;
            this.minutes = Collections.unmodifiableList(minutes); this.hash = hash;
        }
    }
    public static Block calculate(CompletedFacts facts) {
        Objects.requireNonNull(facts, "completed facts");
        if (facts.sourceInputRevision<0 || facts.symbol <= 0 || facts.generation <= 0 || facts.version <= 0 || facts.expectedWatermark < 0
                || facts.watermark < facts.expectedWatermark || facts.watermark > facts.stopAt || facts.receivedCutoff < 0)
            throw new IllegalArgumentException("invalid committed boundaries");
        window(facts.fromMinute, facts.toMinute);
        if (facts.toMinute > minute(facts.watermark) || facts.toMinute > minute(facts.stopAt))
            throw new IllegalArgumentException("minute exceeds committed/STOP boundary");
        if (facts.watermark > facts.expectedWatermark && (facts.fromMinute != minute(facts.expectedWatermark + 1)
                || facts.toMinute != minute(facts.watermark))) throw new IllegalArgumentException("incremental block skips committed window");
        Objects.requireNonNull(facts.sourceMinutes, "source minutes"); Objects.requireNonNull(facts.visibleMinutes, "visible minutes");
        if (facts.sourceMinutes.size() > MAX_MINUTES || facts.visibleMinutes.size() > MAX_MINUTES)
            throw new IllegalArgumentException("fact block exceeds minute limit");
        TreeMap<Long,Minute> result = new TreeMap<>();
        for (SourceMinute source : facts.sourceMinutes) {
            if (source.receivedAt < 0 || source.receivedAt > facts.receivedCutoff)
                throw new IllegalArgumentException("source candle is outside receive cutoff");
            Minute row = encoded(source.body, false, facts);
            if (result.put(row.minuteAt, row) != null) throw new IllegalArgumentException("duplicate source minute");
        }
        Set<Long> seen = new HashSet<>();
        for (VisibleMinute visible : facts.visibleMinutes) {
            if (visible.lastIncludedAt < 0 || visible.lastIncludedAt > facts.receivedCutoff
                    || visible.lastIncludedAt > facts.watermark || visible.lastIncludedAt > facts.stopAt)
                throw new IllegalArgumentException("visible minute exceeds receive/STOP cutoff");
            Minute row = encoded(visible.body, true, facts);
            if (!seen.add(row.minuteAt)) throw new IllegalArgumentException("duplicate visible minute");
            result.put(row.minuteAt, row); // Frozen prefix and published control always win over later whole source OHLC.
        }
        List<Minute> minutes = new ArrayList<>(result.values());
        StringBuilder digest = new StringBuilder().append(facts.symbol).append(':').append(facts.generation).append(':')
            .append(facts.version).append(':').append(facts.expectedWatermark).append(':').append(facts.watermark).append(':')
            .append(facts.fromMinute).append(':').append(facts.toMinute).append(':').append(facts.stopAt).append(':').append(facts.receivedCutoff);
        // Version 0 retains existing generic/S4 hash bytes; real SOURCE input revisions are bound explicitly.
        if(facts.sourceInputRevision>0) digest.append(":source-input:").append(facts.sourceInputRevision);
        for (Minute row : minutes) digest.append('\n').append(row.minuteAt).append(':').append(row.mixed).append(':').append(row.body);
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(digest.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(); for (byte value : bytes) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            return new Block(facts, minutes, hash.toString());
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static Minute encoded(Map<String,Object> body, boolean mixed, CompletedFacts facts) {
        Objects.requireNonNull(body, "minute body");
        long at = ControlHistoryStore.time(body);
        if (at < facts.fromMinute || at > facts.toMinute || Math.floorMod(at, 60000) != 0)
            throw new IllegalArgumentException("minute outside bounded window");
        for (String field : Arrays.asList("open_price", "high_price", "low_price", "close_price")) {
            Object price = body.get(field);
            if (price == null || new BigDecimal(price.toString()).signum() <= 0) throw new IllegalArgumentException("invalid " + field);
        }
        BigDecimal open = ControlHistoryStore.number(body.get("open_price")), close = ControlHistoryStore.number(body.get("close_price"));
        BigDecimal high = ControlHistoryStore.number(body.get("high_price")), low = ControlHistoryStore.number(body.get("low_price"));
        if (high.compareTo(open.max(close)) < 0 || low.compareTo(open.min(close)) > 0 || high.compareTo(low) < 0)
            throw new IllegalArgumentException("inconsistent minute OHLC");
        try { return new Minute(at, JSON.writeValueAsString(body), mixed); }
        catch (Exception invalid) { throw new IllegalArgumentException("invalid minute body", invalid); }
    }
    static long minute(long at) { return Math.floorDiv(at, 60000) * 60000; }
    static void window(long from, long to) {
        if (from < 0 || to < from || Math.floorMod(from, 60000) != 0 || Math.floorMod(to, 60000) != 0
                || (to - from) / 60000 >= MAX_MINUTES) throw new IllegalArgumentException("unbounded/unaligned minute window");
    }
}
