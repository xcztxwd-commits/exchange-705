package com.gtcfesk.exchange.market;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

@RestController
@RequestMapping("/api/market/home-sparkline")
public class HomeSparklineController {
    private final HomeSparklineCache cache;
    public HomeSparklineController(HomeSparklineCache cache) { this.cache=cache; }
    @PostMapping("/batch")
    public ResponseEntity<?> read(@RequestBody Map<String,Object> request) {
        List<String> symbols=MarketPriceController.requestedSymbols(request);
        if(symbols==null) return ResponseEntity.badRequest().body(Collections.singletonMap("error","symbols must contain 1-512 strings"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(MarketPriceController.response(cache.read(symbols)));
    }
}
