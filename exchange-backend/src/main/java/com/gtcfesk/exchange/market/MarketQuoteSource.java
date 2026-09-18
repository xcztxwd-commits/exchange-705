package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;


import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 外汇/贵金属/加密货币行情服务（基于外汇数据接口文档）
 *
 * - 单产品K线: GET {baseUrl}/kline?token=...&query=UrlEncode(json)
 * - 批量最新价: GET {baseUrl}/trade-tick?token=...&query=UrlEncode(json)
 *
 * 说明：
 * - 为了尽量不改前端，输出格式保持与现有 Alltick 兼容：ret/msg/data.kline_list、price等字段。
 * - /batch-kline 官方限制最多2根K线；本项目的批量K线接口需要更多数据（如 sparkline），因此这里用循环调用 /kline 实现批量。
 */
@Service
public class MarketQuoteSource {

    @Value("${market.quote.base-url:https://api.bitget.com/api/v3}")
    private String baseUrl;

    @Value("${market.quote.alltick-url:https://quote.alltick.co/quote-b-api}")
    private String alltickUrl;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired private MarketHttp http;
    @Value("${market.quote.yahoo-url:https://query1.finance.yahoo.com/v8/finance}") private String yahooUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();


    private String mapMetalSymbolToBitget(String code) {
        if (code == null) return code;
        // XAUUSD -> XAUUSDT, XAGUSD -> XAGUSDT
        if (code.toUpperCase().endsWith("USD")) {
            return code.toUpperCase() + "T";
        }
        return code.toUpperCase();
    }

    private String mapSymbolToYahoo(String code, String category) {
        if (code == null) return code;
        String upperCode = code.toUpperCase();
        
        if ("Forex".equalsIgnoreCase(category)) {
            // Yahoo Finance 外汇交易对后缀为 =X，如 USDJPY -> USDJPY=X
            if (!upperCode.endsWith("=X")) {
                return upperCode + "=X";
            }
            return upperCode;
        } else if ("Oil".equalsIgnoreCase(category)) {
            if (upperCode.contains("WTI") || upperCode.contains("USOIL") || upperCode.contains("XTI")) return "CL=F";
            if (upperCode.contains("BRENT") || upperCode.contains("UKOIL") || upperCode.contains("XBR")) return "BZ=F";
            return upperCode;
        } else if ("CFD".equalsIgnoreCase(category)) {
            if (upperCode.contains("US30") || upperCode.contains("DJI") || upperCode.contains("WS30")) return "^DJI";
            if (upperCode.contains("SPX") || upperCode.contains("US500")) return "^GSPC";
            if (upperCode.contains("NAS") || upperCode.contains("US100") || upperCode.contains("NDX") || upperCode.contains("IXIC")) return "^IXIC";
            if (upperCode.contains("UK100") || upperCode.contains("FTSE")) return "^FTSE";
            if (upperCode.contains("GER") || upperCode.contains("DAX")) return "^GDAXI";
            if (upperCode.contains("FRA") || upperCode.contains("CAC")) return "^FCHI";
            if (upperCode.contains("JPN") || upperCode.contains("NK225")) return "^N225";
            if (upperCode.contains("HK") || upperCode.contains("HSI")) return "^HSI";
            if (upperCode.contains("AUS") || upperCode.contains("ASX")) return "^AXJO";
            if (upperCode.contains("VIX")) return "^VIX";
            return upperCode;
        } else if ("US".equalsIgnoreCase(category)) {
            // US stocks generally map 1:1, e.g., AAPL -> AAPL
            return upperCode;
        }
        
        return upperCode;
    }

    private String[] convertIntervalToYahoo(String interval) {
        if (interval == null) return new String[]{"1m", "5d"};
        String v = interval.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "1m": return new String[]{"1m", "5d"};
            case "5m": return new String[]{"5m", "5d"};
            case "15m": return new String[]{"15m", "5d"};
            case "30m": return new String[]{"30m", "5d"};
            case "1h":
            case "60m":
            case "2h":
            case "4h":
            case "6h":
            case "12h": return new String[]{"60m", "1mo"};
            case "1d":
            case "d": return new String[]{"1d", "1y"};
            case "1w":
            case "w": return new String[]{"1wk", "5y"};
            case "1mo":
            case "m":
            case "mo": return new String[]{"1mo", "10y"};
            default: return new String[]{"1m", "5d"};
        }
    }

    Map<String, Object> getKline(String code, String interval, Integer limit, String category) {
        int requiredLimit = (limit != null && limit > 0) ? limit : 100;

        try {
            Map<String, Object> normalized;
            
            // 路由判断：如果是 Crypto 使用 Bitget，否则使用 Alltick
            if ("Crypto".equalsIgnoreCase(category) || category == null) {
                String intervalStr = convertIntervalToBitgetInterval(interval);
                int queryNum = Math.min(requiredLimit, 1000); // Bitget max 1000

                // URL: /market/candles?category=SPOT&symbol={code}&interval={interval}&limit={limit}
                String urlStr = baseUrl + "/market/candles?category=SPOT&symbol=" + urlEncode(code) + "&interval=" + urlEncode(intervalStr) + "&limit=" + queryNum;
                URI uri = URI.create(urlStr);

                HttpHeaders headers = new HttpHeaders();
                headers.add("User-Agent", "Mozilla/5.0");
                headers.add("Accept", "application/json");
                HttpEntity<String> entity = new HttpEntity<>(headers);

                ResponseEntity<String> resp = http.get(uri);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                    throw new RuntimeException("行情K线接口请求失败: " + resp.getStatusCode());
                }

                Map<String, Object> raw = objectMapper.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {});
                normalized = normalizeSingleKlineResponse(raw, code, interval);
            } else if ("Metal".equalsIgnoreCase(category)) {
                // Metal: 使用 Bitget USDT-FUTURES (mix)
                String bitgetSymbol = mapMetalSymbolToBitget(code);
                String intervalStr = convertIntervalToBitgetInterval(interval);
                int queryNum = Math.min(requiredLimit, 1000);

                // Let's use v3 /market/candles?category=USDT-FUTURES
                String urlStr = baseUrl + "/market/candles?category=USDT-FUTURES&symbol=" + urlEncode(bitgetSymbol) + "&interval=" + urlEncode(intervalStr) + "&limit=" + queryNum;
                URI uri = URI.create(urlStr);

                HttpHeaders headers = new HttpHeaders();
                headers.add("User-Agent", "Mozilla/5.0");
                headers.add("Accept", "application/json");
                HttpEntity<String> entity = new HttpEntity<>(headers);

                ResponseEntity<String> resp = http.get(uri);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                    throw new RuntimeException("行情K线接口(Metal)请求失败: " + resp.getStatusCode());
                }

                Map<String, Object> raw = objectMapper.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {});
                normalized = normalizeSingleKlineResponse(raw, code, interval);
            } else if ("Forex".equalsIgnoreCase(category) || "US".equalsIgnoreCase(category) || "CFD".equalsIgnoreCase(category) || "Oil".equalsIgnoreCase(category)) {
                // 使用 Yahoo Finance API
                String yahooSymbol = mapSymbolToYahoo(code, category);
                String[] yahooIntervalRange = convertIntervalToYahoo(interval);
                String yahooInterval = yahooIntervalRange[0];
                String yahooRange = yahooIntervalRange[1];

                // URL: https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range={range}&interval={interval}
                String urlStr = yahooUrl + "/chart/" + urlEncode(yahooSymbol) + "?range=" + yahooRange + "&interval=" + yahooInterval;
                URI uri = URI.create(urlStr);

                HttpHeaders headers = new HttpHeaders();
                headers.add("User-Agent", "Mozilla/5.0");
                headers.add("Accept", "application/json");
                HttpEntity<String> entity = new HttpEntity<>(headers);

                ResponseEntity<String> resp = http.get(uri);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                    throw new RuntimeException("行情K线接口(Yahoo)请求失败: " + resp.getStatusCode());
                }

                Map<String, Object> raw = objectMapper.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {});
                normalized = normalizeYahooKlineResponse(raw, code, interval, requiredLimit);
            } else {
                // Forex 等其他: 使用 Alltick
                String token = ensureTokenConfigured();
                int klineType = convertIntervalToKlineType(interval);
                int queryNum = Math.min(requiredLimit, 500);

                Map<String, Object> query = new LinkedHashMap<>();
                query.put("trace", UUID.randomUUID().toString());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("code", code);
                data.put("kline_type", klineType);
                data.put("kline_timestamp_end", 0);
                data.put("query_kline_num", queryNum);
                data.put("adjust_type", 0);
                query.put("data", data);

                String queryEncoded = urlEncodeJson(query);
                String urlStr = alltickUrl + "/kline?token=" + urlEncode(token) + "&query=" + queryEncoded;
                URI uri = URI.create(urlStr);

                HttpHeaders headers = new HttpHeaders();
                headers.add("User-Agent", "Mozilla/5.0");
                headers.add("Accept", "application/json");
                HttpEntity<String> entity = new HttpEntity<>(headers);

                ResponseEntity<String> resp = http.get(uri);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                    throw new RuntimeException("行情K线接口请求失败: " + resp.getStatusCode());
                }

                Map<String, Object> raw = objectMapper.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {});
                normalized = normalizeAlltickSingleKlineResponse(raw, code, interval);
            }

            return normalized;
        } catch (Exception e) {
            if (e instanceof MarketHttp.Failure) throw (MarketHttp.Failure) e;
            throw new MarketHttp.Failure("invalid_response", 0);
        }
    }

    Map<String, Map<String, Object>> getBatchPrices(List<String> codes, String category) {
        if (codes == null || codes.isEmpty()) {
            return new HashMap<>();
        }
        try {
            if ("Crypto".equalsIgnoreCase(category) || category == null) {
                // Since we have multiple codes, we fetch all SPOT tickers and filter
                String urlStr = baseUrl + "/market/tickers?category=SPOT";
                URI uri = URI.create(urlStr);

                HttpHeaders headers = new HttpHeaders();
                headers.add("User-Agent", "Mozilla/5.0");
                headers.add("Accept", "application/json");
                HttpEntity<String> entity = new HttpEntity<>(headers);

                ResponseEntity<String> resp = http.get(uri);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                    throw new RuntimeException("行情最新价接口请求失败: " + resp.getStatusCode());
                }

                Map<String, Object> raw = objectMapper.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {});
                Map<String, Map<String, Object>> allPrices = normalizeTickResponseToPriceMap(raw);

                Map<String, Map<String, Object>> prices = new HashMap<>();
                for (String code : codes) {
                    if (allPrices.containsKey(code)) {
                        prices.put(code, allPrices.get(code));
                    }
                }



                return prices;
            } else if ("Metal".equalsIgnoreCase(category)) {
                // Fetch all USDT-FUTURES tickers and filter
                String urlStr = baseUrl + "/market/tickers?category=USDT-FUTURES";
                URI uri = URI.create(urlStr);

                HttpHeaders headers = new HttpHeaders();
                headers.add("User-Agent", "Mozilla/5.0");
                headers.add("Accept", "application/json");
                HttpEntity<String> entity = new HttpEntity<>(headers);

                ResponseEntity<String> resp = http.get(uri);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                    throw new RuntimeException("行情最新价接口(Metal)请求失败: " + resp.getStatusCode());
                }

                Map<String, Object> raw = objectMapper.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {});
                Map<String, Map<String, Object>> allPrices = normalizeTickResponseToPriceMap(raw);

                Map<String, Map<String, Object>> prices = new HashMap<>();
                for (String code : codes) {
                    String bitgetSymbol = mapMetalSymbolToBitget(code);
                    if (allPrices.containsKey(bitgetSymbol)) {
                        Map<String, Object> found = allPrices.get(bitgetSymbol);
                        found.put("symbol", code); // restore internal code
                        prices.put(code, found);
                    }
                }



                return prices;
            } else if ("Forex".equalsIgnoreCase(category) || "US".equalsIgnoreCase(category) || "CFD".equalsIgnoreCase(category) || "Oil".equalsIgnoreCase(category)) {
                String yahooSymbols = codes.stream()
                        .map(c -> mapSymbolToYahoo(c, category))
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                
                String urlStr = yahooUrl + "/spark?symbols=" + urlEncode(yahooSymbols) + "&range=1d&interval=1m";
                URI uri = URI.create(urlStr);

                HttpHeaders headers = new HttpHeaders();
                headers.add("User-Agent", "Mozilla/5.0");
                headers.add("Accept", "application/json");
                HttpEntity<String> entity = new HttpEntity<>(headers);

                ResponseEntity<String> resp = http.get(uri);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                    throw new RuntimeException("批量获取最新价失败(Yahoo): " + resp.getStatusCode());
                }

                Map<String, Object> raw = objectMapper.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {});
                Map<String, Map<String, Object>> allPrices = normalizeYahooSparkResponse(raw);

                Map<String, Map<String, Object>> prices = new HashMap<>();
                for (String code : codes) {
                    String yahooSymbol = mapSymbolToYahoo(code, category);
                    if (allPrices.containsKey(yahooSymbol)) {
                        Map<String, Object> found = allPrices.get(yahooSymbol);
                        found.put("symbol", code); // restore internal code
                        prices.put(code, found);
                    }
                }



                return prices;
            } else {
                // Alltick
                String token = ensureTokenConfigured();
                Map<String, Object> query = new LinkedHashMap<>();
                query.put("trace", UUID.randomUUID().toString());
                Map<String, Object> data = new LinkedHashMap<>();
                List<Map<String, Object>> symbolList = new ArrayList<>();
                for (String c : codes) {
                    symbolList.add(Collections.singletonMap("code", c));
                }
                data.put("symbol_list", symbolList);
                query.put("data", data);

                String urlStr = alltickUrl + "/trade-tick?token=" + urlEncode(token) + "&query=" + urlEncodeJson(query);
                URI uri = URI.create(urlStr);

                HttpHeaders headers = new HttpHeaders();
                headers.add("User-Agent", "Mozilla/5.0");
                headers.add("Accept", "application/json");
                HttpEntity<String> entity = new HttpEntity<>(headers);

                ResponseEntity<String> resp = http.get(uri);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                    throw new RuntimeException("行情最新价接口请求失败: " + resp.getStatusCode());
                }

                Map<String, Object> raw = objectMapper.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {});
                Map<String, Map<String, Object>> prices = normalizeAlltickTickResponseToPriceMap(raw);



                return prices;
            }
        } catch (Exception e) {
            if (e instanceof MarketHttp.Failure) throw (MarketHttp.Failure) e;
            throw new MarketHttp.Failure("invalid_response", 0);
        }
    }


    private Map<String, Object> normalizeYahooKlineResponse(Map<String, Object> raw, String code, String interval, int requiredLimit) {
        Map<String, Object> result = new HashMap<>();
        result.put("ret", 200);
        result.put("msg", "ok");

        Map<String, Object> dataOut = new HashMap<>();
        dataOut.put("code", code);
        List<Map<String, Object>> normalizedList = new ArrayList<>();

        try {
            Map<?, ?> chart = (Map<?, ?>) raw.get("chart");
            List<?> resultList = (List<?>) chart.get("result");
            if (resultList != null && !resultList.isEmpty()) {
                Map<?, ?> resultObj = (Map<?, ?>) resultList.get(0);
                List<?> timestamps = (List<?>) resultObj.get("timestamp");
                Map<?, ?> indicators = (Map<?, ?>) resultObj.get("indicators");
                List<?> quoteList = (List<?>) indicators.get("quote");
                
                if (timestamps != null && quoteList != null && !quoteList.isEmpty()) {
                    Map<?, ?> quote = (Map<?, ?>) quoteList.get(0);
                    List<?> opens = (List<?>) quote.get("open");
                    List<?> highs = (List<?>) quote.get("high");
                    List<?> lows = (List<?>) quote.get("low");
                    List<?> closes = (List<?>) quote.get("close");
                    List<?> volumes = (List<?>) quote.get("volume");

                    for (int i = 0; i < timestamps.size(); i++) {
                        if (opens.get(i) == null || closes.get(i) == null) continue; // 过滤空数据

                        Map<String, Object> out = new HashMap<>();
                        // Yahoo 返回的是秒级时间戳
                        long ts = parseLong(timestamps.get(i));
                        out.put("timestamp", ts);
                        out.put("open_price", parseDouble(opens.get(i)));
                        out.put("high_price", parseDouble(highs.get(i)));
                        out.put("low_price", parseDouble(lows.get(i)));
                        out.put("close_price", parseDouble(closes.get(i)));
                        out.put("volume", parseDouble(volumes.get(i)));
                        out.put("turnover", 0.0);
                        normalizedList.add(out);
                    }
                }
            }
        } catch (Exception e) {
            // Malformed items are rejected by the snapshot owner.
        }

        // 按 timestamp 升序排序
        normalizedList.sort(Comparator.comparingLong(m -> ((Number) m.getOrDefault("timestamp", 0L)).longValue()));
        
        // 截取需要的 limit
        if (normalizedList.size() > requiredLimit) {
            normalizedList = normalizedList.subList(normalizedList.size() - requiredLimit, normalizedList.size());
        }
        
        dataOut.put("kline_list", normalizedList);
        result.put("data", dataOut);
        return result;
    }

    private Map<String, Map<String, Object>> normalizeYahooSparkResponse(Map<String, Object> raw) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        try {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                String symbol = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof Map && !"spark".equals(symbol)) {
                    Map<?, ?> dataMap = (Map<?, ?>) value;
                    List<?> closes = (List<?>) dataMap.get("close");
                    List<?> timestamps = (List<?>) dataMap.get("timestamp");
                    
                    if (closes != null && !closes.isEmpty() && timestamps != null && !timestamps.isEmpty()) {
                        Double price = parseDouble(closes.get(closes.size() - 1));
                        Long tickTime = parseLong(timestamps.get(timestamps.size() - 1)) * 1000; // 转为毫秒
                        
                        Map<String, Object> priceData = new HashMap<>();
                        priceData.put("symbol", symbol);
                        priceData.put("price", price != null ? price : 0.0);
                        priceData.put("timestamp", tickTime);
                        
                        Double prevClose = parseDouble(dataMap.get("previousClose"));
                        if (prevClose == null) {
                            prevClose = parseDouble(dataMap.get("chartPreviousClose"));
                        }
                        
                        double change24h = 0.0;
                        double changePct24h = 0.0;
                        if (price != null && prevClose != null && prevClose > 0) {
                            change24h = price - prevClose;
                            changePct24h = (change24h / prevClose) * 100.0;
                        }
                        
                        // 即使 Yahoo 给了 previousClose，我们还是通过统一的 savePriceWith24h 处理，或者这里直接赋值
                        // 这里我们优先将解析出的数据存入，让 savePriceWith24h 来做兜底，为了避免 savePriceWith24h 覆盖，我们不覆盖它
                        priceData.put("change24h", change24h);
                        priceData.put("changePct24h", changePct24h);
                        
                        result.put(symbol, priceData);
                    }
                } else if ("spark".equals(symbol)) {
                    // Yahoo Finance query1 spark response is usually nested under spark.result
                    Map<?, ?> sparkObj = (Map<?, ?>) value;
                    List<?> resultList = (List<?>) sparkObj.get("result");
                    if (resultList != null) {
                        for (Object resItem : resultList) {
                            Map<?, ?> resMap = (Map<?, ?>) resItem;
                            String resSymbol = String.valueOf(resMap.get("symbol"));
                            List<?> resCloses = (List<?>) resMap.get("response"); // spark API array of dict with meta, etc. (format varies)
                            
                            // Yahoo Spark API v8 format: result -> [ { symbol: "AAPL", response: [ { meta: {...} } ] } ]
                            List<?> responses = (List<?>) resMap.get("response");
                            if (responses != null && !responses.isEmpty()) {
                                Map<?, ?> responseObj = (Map<?, ?>) responses.get(0);
                                Map<?, ?> meta = (Map<?, ?>) responseObj.get("meta");
                                if (meta != null) {
                                    Double price = parseDouble(meta.get("regularMarketPrice"));
                                    Double prevClose = parseDouble(meta.get("chartPreviousClose"));
                                    if (prevClose == null) prevClose = parseDouble(meta.get("previousClose"));
                                    
                                    Map<String, Object> priceData = new HashMap<>();
                                    priceData.put("symbol", resSymbol);
                                    priceData.put("price", price != null ? price : 0.0);
                                    priceData.put("timestamp", parseLong(meta.get("regularMarketTime")) == null ? null : parseLong(meta.get("regularMarketTime")) * 1000);
                                    
                                    double change24h = 0.0;
                                    double changePct24h = 0.0;
                                    if (price != null && prevClose != null && prevClose > 0) {
                                        change24h = price - prevClose;
                                        changePct24h = (change24h / prevClose) * 100.0;
                                    }
                                    priceData.put("change24h", change24h);
                                    priceData.put("changePct24h", changePct24h);
                                    
                                    result.put(resSymbol, priceData);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Malformed items are rejected by the snapshot owner.
        }
        return result;
    }

    private Map<String, Object> normalizeAlltickSingleKlineResponse(Map<String, Object> raw, String code, String interval) {
        Map<String, Object> result = new HashMap<>();
        int ret = raw.get("ret") instanceof Number ? ((Number) raw.get("ret")).intValue() : 500;
        result.put("ret", ret);
        result.put("msg", String.valueOf(raw.getOrDefault("msg", "ok")));

        Map<String, Object> dataOut = new HashMap<>();
        dataOut.put("code", code);

        Object data = raw.get("data");
        if (data instanceof Map) {
            Object klineListObj = ((Map<?, ?>) data).get("kline_list");
            if (klineListObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) klineListObj;
                List<Map<String, Object>> normalizedList = new ArrayList<>();
                for (Map<String, Object> item : list) {
                    normalizedList.add(normalizeAlltickKlineItem(item));
                }
                // 按 timestamp 升序排序，保证前端画图一致
                normalizedList.sort(Comparator.comparingLong(m -> ((Number) m.getOrDefault("timestamp", 0L)).longValue()));
                dataOut.put("kline_list", normalizedList);
            } else {
                dataOut.put("kline_list", Collections.emptyList());
            }
        } else {
            dataOut.put("kline_list", Collections.emptyList());
        }

        result.put("data", dataOut);
        return result;
    }

    private Map<String, Object> normalizeAlltickKlineItem(Map<String, Object> item) {
        Map<String, Object> out = new HashMap<>();
        out.put("timestamp", parseLong(item.get("timestamp")));
        out.put("open_price", parseDouble(item.get("open_price")));
        out.put("close_price", parseDouble(item.get("close_price")));
        out.put("high_price", parseDouble(item.get("high_price")));
        out.put("low_price", parseDouble(item.get("low_price")));
        out.put("volume", parseDouble(item.get("volume")));
        out.put("turnover", parseDouble(item.get("turnover")));
        return out;
    }

    private Map<String, Map<String, Object>> normalizeAlltickTickResponseToPriceMap(Map<String, Object> raw) {
        if (!Integer.valueOf(200).equals(raw.get("ret"))) throw new MarketHttp.Failure("invalid_response", 0);
        Map<String, Map<String, Object>> result = new HashMap<>();
        Object data = raw.get("data");
        if (!(data instanceof Map)) {
            return result;
        }
        Object tickListObj = ((Map<?, ?>) data).get("tick_list");
        if (!(tickListObj instanceof List)) {
            return result;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tickList = (List<Map<String, Object>>) tickListObj;
        for (Map<String, Object> t : tickList) {
            String code = t.get("code") != null ? String.valueOf(t.get("code")) : null;
            if (code == null || code.isEmpty()) continue;
            Double price = parseDouble(t.get("price"));
            Long tickTime = parseLong(t.get("tick_time")); // 毫秒
            Map<String, Object> priceData = new HashMap<>();
            priceData.put("symbol", code);
            priceData.put("price", price != null ? price : 0.0);
            priceData.put("timestamp", tickTime);
            // change24h / changePct24h 由Redis保存时计算；这里先填0，WS/HTTP侧会覆写
            priceData.put("change24h", 0.0);
            priceData.put("changePct24h", 0.0);
            result.put(code, priceData);
        }
        return result;
    }

    private Map<String, Object> normalizeSingleKlineResponse(Map<String, Object> raw, String code, String interval) {
        // { ret:200, msg:"ok", data: { code, kline_list:[{timestamp, open_price, high_price, low_price, close_price, volume, turnover}] } }
        Map<String, Object> result = new HashMap<>();
        String bitgetCode = String.valueOf(raw.getOrDefault("code", "500"));
        int ret = "00000".equals(bitgetCode) ? 200 : 500;
        result.put("ret", ret);
        result.put("msg", String.valueOf(raw.getOrDefault("msg", "ok")));

        Map<String, Object> dataOut = new HashMap<>();
        dataOut.put("code", code);

        Object data = raw.get("data");
        if (data instanceof List) {
            @SuppressWarnings("unchecked")
            List<List<String>> list = (List<List<String>>) data;
            List<Map<String, Object>> normalizedList = new ArrayList<>();
            for (List<String> item : list) {
                if (item != null && item.size() >= 7) {
                    normalizedList.add(normalizeKlineItem(item));
                }
            }
            // 按 timestamp 升序排序，保证前端画图一致
            normalizedList.sort(Comparator.comparingLong(m -> ((Number) m.getOrDefault("timestamp", 0L)).longValue()));
            dataOut.put("kline_list", normalizedList);
        } else {
            dataOut.put("kline_list", Collections.emptyList());
        }

        result.put("data", dataOut);
        return result;
    }

    private Map<String, Object> normalizeKlineItem(List<String> item) {
        Map<String, Object> out = new HashMap<>();
        // timestamp in Bitget is ms string, Alltick might have been seconds but we parse it.
        // Assuming frontend expects seconds:
        Long tsMs = parseLong(item.get(0));
        out.put("timestamp", tsMs != null ? tsMs / 1000 : 0L); // Convert to seconds to match old format assumption
        out.put("open_price", parseDouble(item.get(1)));
        out.put("high_price", parseDouble(item.get(2)));
        out.put("low_price", parseDouble(item.get(3)));
        out.put("close_price", parseDouble(item.get(4)));
        out.put("volume", parseDouble(item.get(5)));
        out.put("turnover", parseDouble(item.get(6)));
        return out;
    }



    private Map<String, Map<String, Object>> normalizeTickResponseToPriceMap(Map<String, Object> raw) {
        if (!"00000".equals(String.valueOf(raw.get("code")))) throw new MarketHttp.Failure("invalid_response", 0);
        Map<String, Map<String, Object>> result = new HashMap<>();
        Object data = raw.get("data");
        if (!(data instanceof List)) {
            return result;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tickList = (List<Map<String, Object>>) data;
        for (Map<String, Object> t : tickList) {
            String code = t.get("symbol") != null ? String.valueOf(t.get("symbol")) : null;
            if (code == null || code.isEmpty()) continue;
            Double price = parseDouble(t.get("lastPrice"));
            Long tickTime = parseLong(t.get("ts")); // Source quote time, never substitute response/fetch time.
            Map<String, Object> priceData = new HashMap<>();
            priceData.put("symbol", code);
            priceData.put("price", price != null ? price : 0.0);
            priceData.put("timestamp", tickTime);
            // change24h / changePct24h 由Redis保存时计算；这里先填0，WS/HTTP侧会覆写
            priceData.put("change24h", 0.0);
            priceData.put("changePct24h", 0.0);
            result.put(code, priceData);
        }
        return result;
    }

    private int convertIntervalToKlineType(String interval) {
        if (interval == null) return 1;
        String v = interval.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "1m":
                return 1;
            case "5m":
                return 2;
            case "15m":
                return 3;
            case "30m":
                return 4;
            case "1h":
            case "60m":
                return 5;
            case "2h":
                return 6;
            case "4h":
                return 7;
            case "1d":
            case "d":
                return 8;
            case "1w":
            case "w":
                return 9;
            case "1mo":
            case "m":
            case "mo":
                return 10;
            default:
                return 1;
        }
    }

    private String convertIntervalToBitgetInterval(String interval) {
        if (interval == null) return "1m";
        String v = interval.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "1m": return "1m";
            case "3m": return "3m";
            case "5m": return "5m";
            case "15m": return "15m";
            case "30m": return "30m";
            case "1h":
            case "60m": return "1H";
            case "2h": return "1H"; // fallback
            case "4h": return "4H";
            case "6h": return "6H";
            case "12h": return "12H";
            case "1d":
            case "d": return "1D";
            case "1w":
            case "w": return "1W";
            case "1mo":
            case "m":
            case "mo": return "1M";
            default: return "1m";
        }
    }

    private String urlEncodeJson(Object obj) {
        try {
            String json = objectMapper.writeValueAsString(obj);
            return URLEncoder.encode(json, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            if (e instanceof MarketHttp.Failure) throw (MarketHttp.Failure) e;
            throw new MarketHttp.Failure("invalid_response", 0);
        }
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            if (e instanceof MarketHttp.Failure) throw (MarketHttp.Failure) e;
            throw new MarketHttp.Failure("invalid_response", 0);
        }
    }

    /**
     * 确保行情 token 已配置，从 system_config 表中的 market.quote.token 读取。
     */
    private String ensureTokenConfigured() {
        String token = systemConfigService.getConfigValue("market.quote.token");
        if (token == null || token.trim().isEmpty()) {
            throw new RuntimeException("外汇行情 token 未配置，请在后台管理系统「系统设置 -> 行情配置」中设置 market.quote.token");
        }
        
        return token.trim();
    }

    private Double parseDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            return Double.parseDouble(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long parseLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            return Long.parseLong(s);
        } catch (Exception ignore) {
            return null;
        }
    }
}




