package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

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
        try {
            // Retain the last valid quote indefinitely. Freshness is evaluated separately.
            redisTemplate.opsForValue().set(PRICE_PREFIX + symbol, objectMapper.writeValueAsString(quote));
        } catch (Exception e) {
            // In-memory snapshots remain available when Redis is down.
        }
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




