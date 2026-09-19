package com.gtcfesk.exchange.market;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping({"/api/market/kline", "/api/market/redis/kline"})
public class MarketKlineController {
    @Autowired private ForexQuoteMarketService marketService;
    @GetMapping("/history/{symbol}")
    public ResponseEntity<?> history(@PathVariable String symbol, @RequestParam String interval,
            @RequestParam long endTime, @RequestParam(defaultValue = "160") int limit) {
        if (!Arrays.asList("1m", "5m", "15m", "30m", "1h", "1d").contains(interval)
                || limit < 2 || limit > 200 || endTime < 946684800000L
                || endTime > System.currentTimeMillis() + 86400000L)
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "Invalid history window"));
        try {
            return ResponseEntity.ok(marketService.historicalKline(symbol, interval, limit, endTime));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "Unknown symbol"));
        }
    }
    @GetMapping("/{symbol}")
    public ResponseEntity<?> getKline(@PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "100") Integer limit,
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(marketService.internalKline(symbol, interval, limit));
    }
    @PostMapping("/batch")
    public ResponseEntity<?> getBatchKline(@RequestBody Map<String, Object> request) {
        List<String> symbols = MarketPriceController.requestedSymbols(request);
        if (symbols == null) return ResponseEntity.badRequest().body(Collections.singletonMap("error", "symbols must contain 1-512 strings"));
        String interval = String.valueOf(request.getOrDefault("interval", "1m"));
        int limit = request.get("limit") instanceof Number ? ((Number) request.get("limit")).intValue() : 20;
        List<Object> data = new ArrayList<>();
        for (String symbol : symbols) data.add(marketService.internalKline(symbol, interval, limit).get("data"));
        return ResponseEntity.ok(MarketPriceController.response(data));
    }
}
