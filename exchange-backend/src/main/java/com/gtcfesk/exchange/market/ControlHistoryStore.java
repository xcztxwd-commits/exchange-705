package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

/** Durable source snapshots and mixed minutes. The symbol row serializes all writers across processes. */
@Service
@DependsOn("entityManagerFactory")
public class ControlHistoryStore {
    final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public ControlHistoryStore(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db; transactions = new TransactionTemplate(manager);
    }
    @PostConstruct public void migrate() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/create_control_history.sql"))
            .execute(Objects.requireNonNull(db.getDataSource()));
    }
    <T> T locked(long symbol, Supplier<T> operation) {
        return transactions.execute(status -> {
            db.queryForObject("SELECT id FROM trading_symbol WHERE id=? FOR UPDATE", Long.class, symbol);
            return operation.get();
        });
    }
    <T> T transaction(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
    String encode(Map<String, Object> row) {
        try { return json.writeValueAsString(row); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize market history", e); }
    }
    Map<String, Object> decode(String row) {
        try { return json.readValue(row, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid persisted market history", e); }
    }
    static long time(Map<String, Object> row) { return RandomMarketPath.timestamp(row); }
    /** Providers may append a quote-time snapshot to OHLC pages. It is not a completed period. */
    static boolean periodCandle(Map<String,Object> row, String period) {
        long width = RandomMarketPath.duration(period);
        long alignment = width < 3600000 ? width : 60000;
        return time(row) > 0 && Math.floorMod(time(row), alignment) == 0;
    }
    static BigDecimal number(Object value) { return new BigDecimal(value.toString()); }
    static final class PricePoint {
        final long generatedAt;
        final BigDecimal price;
        PricePoint(long generatedAt, BigDecimal price) { this.generatedAt = generatedAt; this.price = price; }
    }
    /** Backfill uses bounded SQL batches and one OHLC write per minute, not several queries per second. */
    void generatedPoints(String task, long symbol, List<PricePoint> points) {
        for (int offset = 0; offset < points.size(); offset += 500) {
            int count = Math.min(500, points.size() - offset);
            String sql = "INSERT INTO market_control_sample(task_id,generated_at,price) VALUES "
                + String.join(",", Collections.nCopies(count, "(?,?,?)"));
            Object[] arguments = new Object[count * 3];
            for (int i = 0; i < count; i++) {
                PricePoint point = points.get(offset + i);
                arguments[i * 3] = task; arguments[i * 3 + 1] = point.generatedAt; arguments[i * 3 + 2] = point.price;
            }
            db.update(sql, arguments);
        }
        long minute = -1, last = -1;
        Map<String, Object> bar = null;
        for (PricePoint point : points) {
            long nextMinute = point.generatedAt / 60000 * 60000;
            if (nextMinute != minute) {
                if (bar != null) saveMinute(symbol, minute, bar, last);
                minute = nextMinute;
                List<Map<String, Object>> saved = mixed(symbol, minute, minute);
                bar = saved.isEmpty() ? new LinkedHashMap<>() : saved.get(0);
                last = saved.isEmpty() ? -1 : db.queryForObject("SELECT last_event FROM market_mixed_minute WHERE symbol_id=? AND minute_at=?", Long.class, symbol, minute);
            }
            if (point.generatedAt <= last) continue;
            addPrice(bar, minute, point.price); last = point.generatedAt;
        }
        if (bar != null) saveMinute(symbol, minute, bar, last);
    }
    private static void addPrice(Map<String, Object> bar, long minute, BigDecimal price) {
        if (bar.isEmpty()) {
            bar.put("timestamp", minute); bar.put("open_price", price); bar.put("high_price", price); bar.put("low_price", price);
            bar.put("volume", 0); bar.put("partial", true); bar.put("controlled", true);
        } else {
            bar.put("high_price", number(bar.get("high_price")).max(price));
            bar.put("low_price", number(bar.get("low_price")).min(price));
        }
        bar.put("close_price", price);
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) ((Map<?, ?>) result.get("data")).get("kline_list");
    }
    public void sourceCandles(long symbol, String period, List<Map<String, Object>> rows, long now) {
        locked(symbol, () -> {
            for (Map<String, Object> row : rows) {
                Map<String, Object> copy = new LinkedHashMap<>(row); copy.put("timestamp", time(row));
                db.update("INSERT INTO market_source_candle(symbol_id,period,candle_at,body,received_at) VALUES(?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE body=VALUES(body),received_at=VALUES(received_at)",
                    symbol, period, time(row), encode(copy), now);
            }
            return null;
        });
    }
    List<Map<String, Object>> candles(long symbol, String period, long from, long to) {
        List<Map<String,Object>> rows = db.query("SELECT body FROM market_source_candle WHERE symbol_id=? AND period=? AND candle_at>=? AND candle_at<=? ORDER BY candle_at",
            (rs, n) -> decode(rs.getString(1)), symbol, period, from, to);
        rows.removeIf(row -> !periodCandle(row, period)); return rows;
    }
    List<Map<String, Object>> mixed(long symbol, long from, long to) {
        return db.query("SELECT body FROM market_mixed_minute WHERE symbol_id=? AND minute_at>=? AND minute_at<=? ORDER BY minute_at",
            (rs, n) -> decode(rs.getString(1)), symbol, from, to);
    }
    /** Freeze only information actually known at activation, never a subsequently downloaded OHLC. */
    void freeze(long symbol, long now) {
        long minute = now / 60000 * 60000;
        if (!mixed(symbol, minute, minute).isEmpty()) return;
        List<Map<String, Object>> source = candles(symbol, "1m", minute, minute);
        long received = minute - 1;
        if (!source.isEmpty()) {
            Map<String, Object> bar = new LinkedHashMap<>(source.get(0));
            bar.put("controlled", true); bar.put("snapshotAt", now); bar.put("partial", true);
            received = storeSnapshotTime(symbol, minute);
            saveMinute(symbol, minute, bar, Math.min(now - 1, received));
        }
        List<Map<String, Object>> ticks = db.queryForList("SELECT received_at,price FROM (" + sourceEvents() + ") ticks WHERE symbol_id=? AND source_time>=? AND source_time<? AND received_at>? AND received_at<=? ORDER BY received_at,event_sequence",
            symbol, minute, minute + 60000, received, now);
        for (Map<String, Object> tick : ticks) point(symbol, ((Number) tick.get("received_at")).longValue(), number(tick.get("price")), true);
    }
    private long storeSnapshotTime(long symbol, long minute) {
        return db.queryForObject("SELECT received_at FROM market_source_candle WHERE symbol_id=? AND period='1m' AND candle_at=?", Long.class, symbol, minute);
    }
    void point(long symbol, long time, BigDecimal price, boolean controlled) {
        long minute = time / 60000 * 60000;
        List<Map<String, Object>> existing = mixed(symbol, minute, minute);
        if (existing.isEmpty() && !controlled) return;
        Map<String, Object> bar;
        if (existing.isEmpty()) {
            bar = new LinkedHashMap<>();
        } else {
            long last = db.queryForObject("SELECT last_event FROM market_mixed_minute WHERE symbol_id=? AND minute_at=?", Long.class, symbol, minute);
            if (time < last) return;
            bar = existing.get(0);
        }
        addPrice(bar, minute, price);
        saveMinute(symbol, minute, bar, time);
    }
    private void saveMinute(long symbol, long minute, Map<String, Object> bar, long last) {
        db.update("INSERT INTO market_mixed_minute(symbol_id,minute_at,body,last_event) VALUES(?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE body=VALUES(body),last_event=VALUES(last_event)", symbol, minute, encode(bar), last);
    }
    static String sourceEvents() {
        return "SELECT symbol_id,source_time,received_at,price,event_sequence FROM market_source_event UNION ALL "
            + "SELECT t.symbol_id,t.source_time,t.received_at,t.price,0 AS event_sequence FROM market_source_tick t "
            + "WHERE NOT EXISTS (SELECT 1 FROM market_source_event e WHERE e.symbol_id=t.symbol_id AND e.source_time=t.source_time AND e.received_at=t.received_at AND e.price=t.price)";
    }
    boolean quote(long symbol, Map<String, Object> quote, long receivedAt) {
        long time = QuoteState.time(quote.get("timestamp"));
        String eventId = String.valueOf(quote.getOrDefault("eventId", UUID.randomUUID().toString()));
        if (db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE symbol_id=? AND event_id=?", Integer.class, symbol, eventId) > 0) return false;
        db.update("INSERT INTO market_source_event(event_id,symbol_id,source_time,received_at,price) VALUES(?,?,?,?,?)",
            eventId, symbol, time, receivedAt, quote.get("price"));
        db.update("INSERT INTO market_source_quote(symbol_id,price,source_time) VALUES(?,?,?) "
            + "ON DUPLICATE KEY UPDATE price=CASE WHEN source_time<=VALUES(source_time) THEN VALUES(price) ELSE price END,source_time=GREATEST(source_time,VALUES(source_time))",
            symbol, quote.get("price"), time);
        db.update("INSERT INTO market_source_tick(symbol_id,source_time,received_at,price) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE source_time=VALUES(source_time)",
            symbol, time, receivedAt, quote.get("price"));
        return true;
    }
    Map<String, Object> lastQuote(long symbol) {
        List<Map<String, Object>> rows = db.query("SELECT price,source_time FROM market_source_quote WHERE symbol_id=?", (rs, n) -> {
            Map<String, Object> row = new HashMap<>(); row.put("price", rs.getBigDecimal(1)); row.put("timestamp", rs.getLong(2)); return row;
        }, symbol);
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }
    Map<String, Object> lastClose(long symbol, long now) {
        List<Map<String, Object>> rows = db.query("SELECT body,period,received_at FROM market_source_candle WHERE symbol_id=? AND candle_at<? ORDER BY candle_at DESC LIMIT 100",
            (rs, n) -> { Map<String, Object> row = decode(rs.getString(1)); row.put("period", rs.getString(2)); row.put("receivedAt", rs.getLong(3)); return row; }, symbol, now);
        Map<String, Object> latest = Collections.emptyMap();
        long latestClose = 0;
        for (Map<String, Object> row : rows) {
            if (!periodCandle(row, String.valueOf(row.get("period")))) continue;
            long close = time(row) + RandomMarketPath.duration(String.valueOf(row.get("period")));
            // A snapshot fetched while the candle was open does not become a confirmed close merely because time passed.
            if (close <= now && QuoteState.time(row.get("receivedAt")) >= close && close > latestClose) { latest = row; latestClose = close; }
        }
        return latest;
    }
}
