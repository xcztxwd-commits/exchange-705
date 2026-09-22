package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * Redis市场数据服务
 * 用于缓存K线数据和价格数据
 */
@Service
public class RedisMarketService {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @org.springframework.beans.factory.annotation.Value("${market.quote.max-age-ms:15000}") private long maxAgeMs = 15000;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentMap<String, Map<String,Object>> pendingPrices = new ConcurrentHashMap<>();
    private final ScheduledExecutorService priceWriter = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-redis"));
    private volatile long lastPriceWriteError;
    @PostConstruct public void startPriceWriter() { priceWriter.scheduleWithFixedDelay(this::flushPrices, 250, 250, TimeUnit.MILLISECONDS); }
    void flushPrices() {
        pendingPrices.forEach((symbol, quote) -> {
            if (!pendingPrices.remove(symbol, quote)) return;
            try {
                redisTemplate.opsForValue().set(PRICE_PREFIX + symbol, objectMapper.writeValueAsString(quote));
            } catch (Exception failure) {
                pendingPrices.putIfAbsent(symbol, quote);
                if (System.currentTimeMillis() - lastPriceWriteError > 30000) {
                    lastPriceWriteError = System.currentTimeMillis();
                    org.slf4j.LoggerFactory.getLogger(RedisMarketService.class).warn("Market snapshot persistence unavailable; retaining latest pending values");
                }
            }
        });
    }
    @PreDestroy public void stopPriceWriter() { priceWriter.shutdownNow(); flushPrices(); }
    
    // Redis键前缀
    private static final String KLINE_PREFIX = "market:kline:";
    private static final String PRICE_PREFIX = "market:price:";
    private static final String PRICE_24H_PREFIX = "market:price24h:";
    
    // 缓存过期时间（秒）
    private static final long KLINE_EXPIRE_SECONDS = 3600; // 1小时
    private static final long PRICE_EXPIRE_SECONDS = 300; // 5分钟
    
    /**
     * 保存K线数据到Redis
     * @param symbol 交易对符号
     * @param interval K线周期
     * @param klineList K线数据列表
     */
    public void saveKlines(String symbol, String interval, List<Map<String, Object>> klineList) {
        try {
            String key = KLINE_PREFIX + symbol + ":" + interval;
            String json = objectMapper.writeValueAsString(klineList);
            redisTemplate.opsForValue().set(key, json, KLINE_EXPIRE_SECONDS, TimeUnit.SECONDS);
            System.out.println("[RedisMarketService] Saved " + klineList.size() + " klines for " + symbol + ":" + interval);
        } catch (Exception e) {
            System.err.println("[RedisMarketService] Failed to save klines: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void saveSimulationHistory(String session, String interval, List<Map<String, Object>> rows) {
        try {
            redisTemplate.opsForValue().set(KLINE_PREFIX + session + ":" + interval, objectMapper.writeValueAsString(rows));
        } catch (Exception failure) {
            throw new IllegalStateException("无法保存随机行情的历史快照", failure);
        }
    }

    /**
     * 从Redis获取K线数据
     * @param symbol 交易对符号
     * @param interval K线周期
     * @return K线数据列表，如果不存在则返回null
     */
    public List<Map<String, Object>> getKlines(String symbol, String interval) {
        try {
            String key = KLINE_PREFIX + symbol + ":" + interval;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            System.err.println("[RedisMarketService] Failed to get klines: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 批量获取K线数据
     * @param symbols 交易对符号列表
     * @param interval K线周期
     * @return 交易对符号到K线数据的映射
     */
    public Map<String, List<Map<String, Object>>> getBatchKlines(List<String> symbols, String interval) {
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        for (String symbol : symbols) {
            List<Map<String, Object>> klines = getKlines(symbol, interval);
            if (klines != null && !klines.isEmpty()) {
                result.put(symbol, klines);
            }
        }
        return result;
    }
    
    /**
     * 保存价格数据到Redis
     * @param symbol 交易对符号
     * @param price 价格
     * @param change24h 24小时涨跌额
     * @param changePct24h 24小时涨跌幅
     */
    public void savePrice(String symbol, Map<String, Object> quote) {
        if (!QuoteState.valid(quote)) return;
        // Only persistence snapshots coalesce; authoritative source events are handled separately.
        pendingPrices.put(symbol, new HashMap<>(quote));
    }
    /**
     * 从Redis获取价格数据
     * @param symbol 交易对符号
     * @return 价格数据，如果不存在则返回null
     */
    public Map<String, Object> getPrice(String symbol) {
        try {
            String key = PRICE_PREFIX + symbol;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return QuoteState.view(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}), maxAgeMs);
        } catch (Exception e) {
            System.err.println("[RedisMarketService] Failed to get price: " + e.getMessage());
            return null;
        }
    }
    
    @Autowired private com.gtcfesk.exchange.admin.SystemConfigService configs;
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<String> CONVERSION_SCRIPT =
        new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "local now=tonumber(ARGV[1]); local duration=tonumber(ARGV[2]); " +
            "local old=redis.call('GET',KEYS[1]); " +
            "if old then local rate=cjson.decode(old); local expires=tonumber(rate.timestamp)+duration; " +
            "if expires>now then rate.expiresAt=expires; local value=cjson.encode(rate); " +
            "redis.call('SET',KEYS[1],value,'PX',expires-now); return value; end; " +
            "redis.call('DEL',KEYS[1]); end; " +
            "if ARGV[3]~='' then local rate=cjson.decode(ARGV[3]); local expires=tonumber(rate.timestamp)+duration; " +
            "if expires>now then rate.expiresAt=expires; local value=cjson.encode(rate); " +
            "redis.call('SET',KEYS[1],value,'PX',expires-now); return value; end; end; return nil;", String.class);

    /** Atomic, fixed settlement snapshot; duration changes apply to the original source timestamp. */
    public Map<String,Object> conversionQuote(String source, String category, String code, Map<String,Object> raw) {
        String key = "market:conversion:" + source + ":" + category + ":" + code;
        try {
            long now = System.currentTimeMillis();
            String configured = configs.getConfigValue("market.conversion.cache-hours");
            long duration = TimeUnit.HOURS.toMillis(com.gtcfesk.exchange.admin.SystemConfigService.conversionCacheHours(configured));
            String candidate = "";
            if (QuoteState.valid(raw) && Boolean.TRUE.equals(raw.get("sourceAvailable"))
                    && now - QuoteState.time(raw.get("fetchedAt")) <= 60000) {
                Map<String,Object> rate = new HashMap<>();
                rate.put("price", raw.get("price")); rate.put("timestamp", Math.min(now, QuoteState.time(raw.get("timestamp"))));
                rate.put("fetchedAt", now); candidate = objectMapper.writeValueAsString(rate);
            }
            String json = redisTemplate.execute(CONVERSION_SCRIPT, Collections.singletonList(key), Long.toString(now), Long.toString(duration), candidate);
            if (json == null) return null;
            Map<String,Object> cached = objectMapper.readValue(json, new TypeReference<Map<String,Object>>() {});
            return QuoteState.valid(cached) && QuoteState.time(cached.get("expiresAt")) > System.currentTimeMillis() ? cached : null;
        } catch (Exception failure) {
            return null; // Never silently use a different settlement rate when Redis is unavailable.
        }
    }

    /**
     * 批量获取价格数据
     * @param symbols 交易对符号列表
     * @return 交易对符号到价格数据的映射
     */
    public Map<String, Map<String, Object>> getBatchPrices(List<String> symbols) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (String symbol : symbols) {
            Map<String, Object> price = getPrice(symbol);
            if (price != null) {
                result.put(symbol, price);
            }
        }
        return result;
    }
    
    /**
     * 保存24小时前的价格（用于计算24小时涨跌幅）
     * @param symbol 交易对符号
     * @param price24hAgo 24小时前的价格
     */
    public void savePrice24hAgo(String symbol, Double price24hAgo) {
        try {
            String key = PRICE_24H_PREFIX + symbol;
            redisTemplate.opsForValue().set(key, String.valueOf(price24hAgo), 86400, TimeUnit.SECONDS); // 24小时过期
        } catch (Exception e) {
            System.err.println("[RedisMarketService] Failed to save price24hAgo: " + e.getMessage());
        }
    }
    
    /**
     * 获取24小时前的价格
     * @param symbol 交易对符号
     * @return 24小时前的价格，如果不存在则返回null
     */
    public Double getPrice24hAgo(String symbol) {
        try {
            String key = PRICE_24H_PREFIX + symbol;
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isEmpty()) {
                return null;
            }
            return Double.parseDouble(value);
        } catch (Exception e) {
            System.err.println("[RedisMarketService] Failed to get price24hAgo: " + e.getMessage());
            return null;
        }
    }
}




