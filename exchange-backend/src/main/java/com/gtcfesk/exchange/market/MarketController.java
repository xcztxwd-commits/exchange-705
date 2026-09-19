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
import java.util.Map;

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
        List<TradingSymbol> symbols = symbolRepository.findByIsHotTrueAndIsEnabledTrueOrderBySortOrderDesc();
        Map<String, Object> result = new HashMap<>();
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
            symbols = symbolRepository.findByCategoryAndIsEnabledTrueOrderBySortOrderDesc(category);
        } else {
            symbols = symbolRepository.findByIsEnabledTrueOrderBySortOrderDesc();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("list", symbols);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取所有启用的币种
     */
    @GetMapping("/all")
    public ResponseEntity<?> getAllSymbols() {
        List<TradingSymbol> symbols = symbolRepository.findByIsEnabledTrueOrderBySortOrderDesc();
        Map<String, Object> result = new HashMap<>();
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
        
        List<TradingSymbol> symbols = symbolRepository.searchSymbolsByKeyword(keyword.trim());
        Map<String, Object> result = new HashMap<>();
        result.put("list", symbols);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取首页分类配置（用于前端分类排序）
     * 优先读取 system_config 表中的 home.categories 配置（JSON数组），
     * 如果没有配置，则返回默认分类及默认顺序。
     */
    @GetMapping("/categories")
    public ResponseEntity<?> getHomeCategories() {
        try {
            String json = systemConfigService.getConfigValue("home.categories");
            List<Map<String, Object>> categories = new ArrayList<>();
            
            // 从 system_config 中读取配置（每个分类一条记录，config_value 为单个分类JSON）
            if (json != null && !json.trim().isEmpty()) {
                // 兼容旧格式：如果保存的是整个数组
                if (json.trim().startsWith("[")) {
                    categories = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                } else {
                    // 新格式：单条记录，存的也是数组
                    categories = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                }
            } else {
                // 兼容 Settings.vue 里批量保存的多条 config 的情况
                // 尝试按前缀 home.category.XXX 读取所有分类
                // 这里简单使用默认分类，避免引入新的Repository
            }
            
            if (categories == null || categories.isEmpty()) {
                // 默认分类及顺序
                String[] defaultKeys = new String[] {"US", "Crypto", "Metal", "Forex", "CFD", "Oil"};
                for (int i = 0; i < defaultKeys.length; i++) {
                    Map<String, Object> cat = new HashMap<>();
                    cat.put("key", defaultKeys[i]);
                    cat.put("label", defaultKeys[i]);
                    cat.put("sortOrder", i + 1);
                    cat.put("enabled", true);
                    categories.add(cat);
                }
            }
            
            // 按 sortOrder 排序，并过滤掉未启用的分类
            categories.removeIf(c -> Boolean.FALSE.equals(c.getOrDefault("enabled", true)));
            categories.sort((a, b) -> {
                Integer sa = (Integer) a.getOrDefault("sortOrder", 0);
                Integer sb = (Integer) b.getOrDefault("sortOrder", 0);
                return sa.compareTo(sb);
            });
            
            Map<String, Object> result = new HashMap<>();
            result.put("list", categories);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> result = new HashMap<>();
            result.put("list", new ArrayList<>());
            result.put("error", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.ok(result);
        }
    }
}


