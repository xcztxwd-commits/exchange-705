package com.gtcfesk.exchange.market;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping({"/api/market/price", "/api/market/redis/price"})
public class MarketPriceController {
    @Autowired private ForexQuoteMarketService marketService;
    @GetMapping("/{symbol}")
    public ResponseEntity<?> getPrice(@PathVariable String symbol) {
        return ResponseEntity.ok(response(marketService.internalPrice(symbol)));
    }
    @PostMapping("/batch")
    public ResponseEntity<?> getBatchPrice(@RequestBody Map<String, Object> request) {
        List<String> symbols = requestedSymbols(request);
        if (symbols == null) return ResponseEntity.badRequest().body(Collections.singletonMap("error", "symbols must contain 1-512 strings"));
        Map<String, Object> data = new LinkedHashMap<>();
        for (String symbol : symbols) data.put(symbol, marketService.internalPrice(symbol));
        return ResponseEntity.ok(response(data));
    }
    static Map<String, Object> response(Object data) {
        Map<String, Object> result = new HashMap<>(); result.put("ret", 200); result.put("msg", "ok"); result.put("data", data); return result;
    }
    @SuppressWarnings("unchecked")
    static List<String> requestedSymbols(Map<String, Object> request) {
        Object list = request.get("symbols");
        if (!(list instanceof List) || ((List<?>) list).isEmpty() || ((List<?>) list).size() > 512) return null;
        for (Object item : (List<?>) list) if (!(item instanceof String) || ((String) item).length() > 64) return null;
        return (List<String>) list;
    }
}
