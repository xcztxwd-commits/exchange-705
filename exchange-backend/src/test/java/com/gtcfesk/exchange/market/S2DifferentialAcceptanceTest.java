package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent S1 source oracle, not two calls into the current algorithm implementation. */
class S2DifferentialAcceptanceTest extends TenantMarketTestContext {
    @TempDir Path compiled;
    static final BigDecimal START = new BigDecimal("100000.00"), TARGET = new BigDecimal("100300.00");
    static final int DURATION = 300, PRECISION = 2, INTENSITY = 10;
    static final Set<String> OLD = Set.of("PriceControlPath", "BalancedControlPlan", "StabilizedControlPlan", "TargetControlSettings", "TargetControlPlan");

    @Test void allFourVersionsMatchFrozenS1AtEverySecondAndAfterColdReload() throws Exception {
        String baseline = System.getenv("S2_BASELINE_DIR");
        assertNotNull(baseline, "S2_BASELINE_DIR must name the immutable before tree; no current-code fallback");
        Path source = Path.of(baseline).toRealPath().resolve("exchange-backend/src/main/java/com/gtcfesk/exchange/market");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK is required for the independent S1 source oracle");
        List<String> arguments = new ArrayList<>(Arrays.asList("-encoding", "UTF-8", "-classpath",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), "-d", compiled.toString()));
        for (String name : new TreeSet<>(OLD)) {
            Path old = source.resolve(name + ".java");
            assertTrue(Files.isRegularFile(old), "missing immutable S1 oracle: " + old);
            arguments.add(old.toString());
        }
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(new String[0])), "independent S1 compilation");
        try (URLClassLoader oracle = new URLClassLoader(new java.net.URL[]{compiled.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                String prefix = "com.gtcfesk.exchange.market.";
                String simple = name.startsWith(prefix) ? name.substring(prefix.length()).split("\\$", 2)[0] : "";
                if (!OLD.contains(simple)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> value = findLoadedClass(name);
                    if (value == null) value = findClass(name);
                    if (resolve) resolveClass(value);
                    return value;
                }
            }
        }) {
            for (int version = 1; version <= 4; version++) compare(version, oracle);
        }
    }

    void compare(int version, ClassLoader oracle) throws Exception {
        DriverManagerDataSource data = new DriverManagerDataSource("jdbc:h2:mem:diff_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ControlHistoryStore store = new ControlHistoryStore(new JdbcTemplate(data), new DataSourceTransactionManager(data));
        MarketSqlFixture.schema(store.db); store.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,1)", version);
        PersistentPriceControl controls = new PersistentPriceControl(store);
        TradingSymbol symbol = new TradingSymbol(); symbol.setTenantId(1L); symbol.setId((long)version); symbol.setSymbol("S2_DIFF"); symbol.setPricePrecision(PRECISION);
        long now = System.currentTimeMillis();
        Map<String,Object> raw = new HashMap<>(); raw.put("price", START); raw.put("timestamp", now); raw.put("sourceTimestamp", now); raw.put("available", true);
        PersistentPriceControl.Task task;
        PersistentPriceControl.Prepared prepared = null;
        if (version == 1) {
            symbol.setControlEnabled(true); symbol.setControlStartedAt(now); symbol.setControlStartPrice(START); symbol.setControlTargetPrice(TARGET);
            symbol.setControlDurationSeconds(DURATION); symbol.setControlIntensity(INTENSITY); symbol.setControlRandomOscillation(false);
            controls.importLegacy(symbol); task = controls.latest(version); symbol.setControlEnabled(false);
        } else if (version == 2) {
            task = controls.start(symbol, raw, START, DURATION, TARGET, INTENSITY, false, false, "diff-v2");
        } else {
            prepared = version == 3 ? controls.prepare(symbol, raw, START, DURATION, TARGET, INTENSITY, false)
                    : controls.prepare(symbol, raw, START, DURATION, TARGET, INTENSITY, false, new TargetControlOptions());
            task = controls.startPrepared(symbol, raw, START, DURATION, TARGET, INTENSITY, false, "diff-v" + version, null, prepared);
        }
        assertEquals(version, task.algorithmVersion);
        TradingSymbol oldSymbol = new TradingSymbol(); org.springframework.beans.BeanUtils.copyProperties(symbol, oldSymbol);
        oldSymbol.setControlStartedAt(task.startedAt); oldSymbol.setControlStartPrice(START); oldSymbol.setControlTargetPrice(TARGET);
        oldSymbol.setControlDurationSeconds(DURATION); oldSymbol.setControlIntensity(INTENSITY); oldSymbol.setControlRandomOscillation(false);
        Object oldPlan = null;
        if (version >= 3) {
            String type = "com.gtcfesk.exchange.market." + (version == 3 ? "BalancedControlPlan" : "StabilizedControlPlan");
            Class<?> planClass = oracle.loadClass(type), parametersClass = oracle.loadClass(type + "$Parameters");
            Object parameters = parametersClass.getConstructor(BigDecimal.class, BigDecimal.class, int.class, int.class, int.class, BigDecimal.class)
                    .newInstance(START, TARGET, DURATION, PRECISION, INTENSITY, new BigDecimal("0.00001"));
            oldPlan = planClass.getMethod("generate", parametersClass, long.class).invoke(null, parameters, prepared.seed);
            assertEquals(oldPlan.getClass().getMethod("checksum").invoke(oldPlan), store.plan(task.id).checksum(), "V" + version + " plan checksum");
        }
        controls.pump(symbol, raw, task.plannedEnd, 60000);
        ControlHistoryStore cold = new ControlHistoryStore(new JdbcTemplate(data), new DataSourceTransactionManager(data));
        PersistentPriceControl.Task reloaded = new PersistentPriceControl(cold).latest(version);
        assertEquals(301, store.db.queryForObject("SELECT COUNT(*) FROM market_control_sample WHERE task_id=?", Integer.class, task.id));
        assertEquals(task.plannedEnd, reloaded.sampledUntil); assertEquals(task.plannedEnd, reloaded.endedAt);
        for (int second = 0; second <= DURATION; second++) {
            long at = task.startedAt + second * 1000L;
            BigDecimal expected = version <= 2
                    ? (BigDecimal)oracle.loadClass("com.gtcfesk.exchange.market.PriceControlPath").getMethod("price", TradingSymbol.class, long.class, int.class).invoke(null, oldSymbol, at, version)
                    : (BigDecimal)oldPlan.getClass().getMethod("price", long.class, long.class).invoke(oldPlan, task.startedAt, at);
            BigDecimal persisted = store.db.queryForObject("SELECT price FROM market_control_sample WHERE task_id=? AND generated_at=?", BigDecimal.class, task.id, at);
            assertEquals(0, expected.compareTo(persisted), "V" + version + " persisted second " + second);
            assertEquals(0, expected.compareTo(reloaded.price(at)), "V" + version + " cold second " + second);
        }
        assertEquals(0, TARGET.compareTo(reloaded.price(task.plannedEnd)));
    }
}
