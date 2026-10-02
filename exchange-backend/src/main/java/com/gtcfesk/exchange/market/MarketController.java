package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/market")
public class MarketController {
    @Autowired private ForexQuoteMarketService quotes;
    @GetMapping("/status") public Object status() { return quotes.sourceStatus(); }
    
    @Autowired
    private TradingSymbolRepository symbolRepository;
    
    @Autowired
    private SystemConfigService systemConfigService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 获取热门币种列表
     */
    @GetMapping("/hot")
    public ResponseEntity<?> getHotSymbols() {
        List<TradingSymbol> symbols = symbolRepository.findByTenantIdAndIsHotTrueAndIsEnabledTrueOrderBySortOrderDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        Map<String, Object> result = new HashMap<>();
        applyCategoryLeverage(symbols);
        result.put("list", symbols);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 根据分类获取币种列表
     */
    @GetMapping("/symbols")
    public ResponseEntity<?> getSymbolsByCategory(@RequestParam(required = false) String category) {
        List<TradingSymbol> symbols;
        if (category != null && !category.isEmpty()) {
            symbols = symbolRepository.findByTenantIdAndCategoryAndIsEnabledTrueOrderBySortOrderDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), category);
        } else {
            symbols = symbolRepository.findByTenantIdAndIsEnabledTrueOrderBySortOrderDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        }
        Map<String, Object> result = new HashMap<>();
        applyCategoryLeverage(symbols);
        result.put("list", symbols);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取所有启用的币种
     */
    @GetMapping("/all")
    public ResponseEntity<?> getAllSymbols() {
        List<TradingSymbol> symbols = symbolRepository.findByTenantIdAndIsEnabledTrueOrderBySortOrderDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        Map<String, Object> result = new HashMap<>();
        applyCategoryLeverage(symbols);
        result.put("list", symbols);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 搜索币种/合约
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchSymbols(@RequestParam String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("list", new ArrayList<>());
            return ResponseEntity.ok(result);
        }
        
        String query = keyword.trim();
        List<TradingSymbol> symbols = new ArrayList<>(symbolRepository.searchSymbolsByKeyword(query));
        String pair = query.toUpperCase(Locale.ROOT).replaceAll("\\s*/\\s*", "/");
        if (pair.matches("[A-Z]{3}/[A-Z]{3}")) {
            String code = pair.startsWith("USD/") ? pair.substring(4) + "=X" : pair.replace("/", "") + "=X";
            for (TradingSymbol match : symbolRepository.searchSymbolsByKeyword(code)) {
                if (pair.equals(match.getDisplayName()) && symbols.stream().noneMatch(existing -> Objects.equals(existing.getId(), match.getId()))) symbols.add(match);
            }
        }
        Map<String, Object> result = new HashMap<>();
        applyCategoryLeverage(symbols);
        result.put("list", symbols);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取首页分类配置（用于前端分类排序）
     * 优先读取 system_config 表中的 home.categories 配置（JSON数组），
     * 如果没有配置，则返回默认分类及默认顺序。
     */
    @Autowired private MarketCategoryService categoryService;
    @GetMapping("/categories")
    public ResponseEntity<?> getHomeCategories() {
        List<Map<String,Object>> rows = categoryService.all();
        rows.removeIf(row -> Boolean.FALSE.equals(row.get("enabled")));
        Map<String,Object> result = new HashMap<>(); result.put("list",rows);
        return ResponseEntity.ok(result);
    }
    private void applyCategoryLeverage(List<TradingSymbol> symbols) {
        java.util.Set<String> disabled = new java.util.HashSet<>();
        for(Map<String,Object> row:categoryService.all()) if(Boolean.FALSE.equals(row.get("leverageEnabled"))) disabled.add((String)row.get("key"));
        for(TradingSymbol symbol:symbols) symbol.setLeverageEnabled(!disabled.contains(symbol.getCategory()));
    }

}
