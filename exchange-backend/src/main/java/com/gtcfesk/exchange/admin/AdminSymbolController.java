package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.admin.dto.SymbolQueryRequest;
import com.gtcfesk.exchange.entity.TradingSymbol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/symbols")
public class AdminSymbolController {
    
    @Autowired
    private AdminSymbolService symbolService;
    
    @Autowired private com.gtcfesk.exchange.market.MarketInstrumentCatalog catalog;
    @Autowired private com.gtcfesk.exchange.market.MarketCategoryService categories;

    @GetMapping("/catalog/sources")
    public Object sources() {return catalog.sources();}
    @GetMapping("/catalog")
    public Object catalog(@RequestParam String source,@RequestParam String sourceCategory,
                          @RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int page) {
        return catalog.list(source,sourceCategory,query,page);
    }
    @GetMapping("/categories")
    public Object categories() {return categories.all();}
    @PostMapping("/categories")
    public Object saveCategories(@RequestBody List<Map<String,Object>> rows) {return categories.save(rows);}
    public static class AddRequest {
        public String source,sourceCategory,projectCategory;
        public List<String> symbols;
    }
    @PostMapping("/catalog/add")
    public Object add(@RequestBody AddRequest body) {
        return symbolService.addFromCatalog(body.source,body.sourceCategory,body.projectCategory,body.symbols);
    }

    @PostMapping("/query")
    public ResponseEntity<?> querySymbols(@RequestBody SymbolQueryRequest req) {
        Page<TradingSymbol> page = symbolService.querySymbols(req);
        Map<String, Object> result = new HashMap<>();
        result.put("list", page.getContent());
        result.put("total", page.getTotalElements());
        result.put("page", page.getNumber());
        result.put("size", page.getSize());
        return ResponseEntity.ok(result);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<?> getSymbolDetail(@PathVariable Long id) {
        TradingSymbol symbol = symbolService.getSymbolDetail(id);
        return ResponseEntity.ok(symbol);
    }
    
    @PostMapping("/update")
    public ResponseEntity<?> updateSymbol(@RequestBody TradingSymbol symbol) {
        TradingSymbol updated = symbolService.updateSymbol(symbol.getId(), symbol);
        Map<String, String> result = new HashMap<>();
        result.put("message", "币种更新成功");
        return ResponseEntity.ok(updated);
    }
    
    @PostMapping("/delete/{id}")
    public ResponseEntity<?> deleteSymbol(@PathVariable Long id) {
        symbolService.deleteSymbol(id);
        Map<String, String> result = new HashMap<>();
        result.put("message", "币种删除成功");
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/toggleHot/{id}")
    public ResponseEntity<?> toggleHot(@PathVariable Long id) {
        symbolService.toggleHot(id);
        Map<String, String> result = new HashMap<>();
        result.put("message", "热门状态更新成功");
        return ResponseEntity.ok(result);
    }
    
    /**
     * 批量设置用户可选杠杆上限
     */
    @PostMapping("/batchSetLeverage")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> batchSetLeverage(@RequestBody Map<String, Object> request) {
        java.math.BigDecimal leverage = new java.math.BigDecimal(request.get("leverage").toString());
        List<Long> symbolIds = null;
        if (request.get("symbolIds") != null) {
            symbolIds = new java.util.ArrayList<>();
            for (Object id : (List<?>) request.get("symbolIds")) {
                symbolIds.add(new java.math.BigDecimal(id.toString()).longValueExact());
            }
        }
        String category = (String) request.get("category");
        
        symbolService.batchSetLeverage(leverage, symbolIds, category);
        Map<String, String> result = new HashMap<>();
        result.put("message", "杠杆上限设置成功");
        return ResponseEntity.ok(result);
    }
}



