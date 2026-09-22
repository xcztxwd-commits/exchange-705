package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;
import java.util.*;

/** Immutable control events keep already displayed virtual candles reproducible. */
public final class SimulationControlPath {
    private SimulationControlPath() { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, List<Event>> CACHE = new LinkedHashMap<String, List<Event>>(16, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, List<Event>> entry) { return size() > 64; }
    };
    public static class Event {
        public long at, startedAt;
        public String mode, symbol;
        public int duration, intensity, precision, algorithmVersion;
        public boolean oscillation;
        public BigDecimal start, target, offset;
        @JsonIgnore public TradingSymbol plan;
        public synchronized TradingSymbol plan() {
            if (plan == null) {
                TradingSymbol p = new TradingSymbol();
                p.setSymbol(symbol); p.setPricePrecision(precision); p.setControlStartedAt(startedAt);
                p.setControlStartPrice(start); p.setControlTargetPrice(target); p.setControlPriceOffset(offset);
                p.setControlDurationSeconds(duration); p.setControlIntensity(intensity); p.setControlRandomOscillation(oscillation);
                plan = p;
            }
            return plan;
        }
        public long endsAt() { return (startedAt + duration * 1000L + 999) / 1000 * 1000; }
    }

    public static synchronized List<Event> events(TradingSymbol symbol) {
        String json = symbol.getRandomMarketControls();
        if (json == null || json.isEmpty()) return Collections.emptyList();
        List<Event> events = CACHE.get(json);
        if (events == null) {
            try { events = JSON.readValue(json, new TypeReference<List<Event>>() {}); }
            catch (Exception invalid) { throw new IllegalStateException("Invalid virtual control history", invalid); }
            CACHE.put(json, events);
        }
        return events;
    }

    public static void record(TradingSymbol symbol, long now) {
        Event event = new Event();
        event.algorithmVersion = 2; // Missing version on saved events denotes the original algorithm.
        event.at = now / 1000 * 1000; event.symbol = symbol.getSymbol(); event.precision = PriceControlPath.precision(symbol);
        event.offset = symbol.getControlPriceOffset() == null ? BigDecimal.ZERO : symbol.getControlPriceOffset();
        event.mode = "offset";
        if (PriceControlPath.running(symbol)) {
            event.mode = Boolean.TRUE.equals(symbol.getControlRestoring()) ? "restore" : "target";
            event.startedAt = symbol.getControlStartedAt(); event.duration = symbol.getControlDurationSeconds();
            event.start = symbol.getControlStartPrice(); event.target = symbol.getControlTargetPrice();
            event.intensity = symbol.getControlIntensity(); event.oscillation = Boolean.TRUE.equals(symbol.getControlRandomOscillation());
        }
        List<Event> updated = new ArrayList<>(events(symbol));
        updated.add(event);
        while (updated.size() > 1 && updated.get(1).at < now - 7 * 86400000L) updated.remove(0);
        try { symbol.setRandomMarketControls(JSON.writeValueAsString(updated)); }
        catch (Exception invalid) { throw new IllegalStateException("Cannot save virtual control history", invalid); }
    }

    public static BigDecimal price(TradingSymbol symbol, long time) {
        long tick = time / 1000 * 1000;
        List<Event> events = events(symbol);
        Event current = null;
        for (int i = events.size() - 1; i >= 0; i--) if (events.get(i).at <= tick) { current = events.get(i); break; }
        BigDecimal base = RandomMarketPath.basePrice(symbol, tick);
        if (current == null) return base;
        BigDecimal result;
        if ("target".equals(current.mode)) {
            result = tick < current.endsAt() ? PriceControlPath.price(current.plan(), tick, current.algorithmVersion == 0 ? 1 : current.algorithmVersion)
                : base.add(current.target.subtract(RandomMarketPath.basePrice(symbol, current.endsAt())));
        } else if ("restore".equals(current.mode)) {
            result = base.add(tick < current.endsAt() ? PriceControlPath.restoreOffset(current.plan(), tick, current.algorithmVersion == 0 ? 1 : current.algorithmVersion) : BigDecimal.ZERO);
        } else result = base.add(current.offset);
        return result.max(BigDecimal.ONE.movePointLeft(PriceControlPath.precision(symbol)));
    }
}
