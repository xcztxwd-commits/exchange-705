package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.admin.dto.SymbolQueryRequest;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.market.RedisMarketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class AdminSymbolService {
    
    @Autowired
    private TradingSymbolRepository symbolRepository;
    
    @Autowired
    private RedisMarketService redisMarketService;
    
    public Page<TradingSymbol> querySymbols(SymbolQueryRequest req) {
        PageRequest pageRequest = PageRequest.of(
            req.getPage(),
            req.getSize(),
            Sort.by(Sort.Direction.DESC, "sortOrder").and(Sort.by(Sort.Direction.ASC, "symbol"))
        );
        Page<TradingSymbol> page = symbolRepository.searchSymbols(req.getCategory(), pageRequest);
        
        // 从Redis填充价格数据
        fillPricesFromRedis(page.getContent());
        
        return page;
    }
    
    /**
     * 从Redis批量获取价格数据并填充到币种列表
     */
    private void fillPricesFromRedis(List<TradingSymbol> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        
        // 收集所有需要查询的alltickSymbol
        for (TradingSymbol symbol : symbols) {
            String alltickSymbol = symbol.getAlltickSymbol();
            if (alltickSymbol == null || alltickSymbol.isEmpty()) {
                alltickSymbol = symbol.getSymbol();
            }
            
            // 从Redis获取价格数据
            Map<String, Object> priceData = redisMarketService.getPrice((symbol.getCategory() == null ? "Crypto" : symbol.getCategory()) + ":" + alltickSymbol);
            if (priceData != null) {
                // 填充当前价格
                Object priceObj = priceData.get("price");
                if (priceObj != null) {
                    try {
                        double price = priceObj instanceof Number 
                            ? ((Number) priceObj).doubleValue() 
                            : Double.parseDouble(priceObj.toString());
                        symbol.setCurrentPrice(BigDecimal.valueOf(price));
                    } catch (Exception e) {
                        System.err.println("[AdminSymbolService] Failed to parse price for " + symbol.getSymbol() + ": " + e.getMessage());
                    }
                }
                
                // 填充24小时涨跌幅
                Object changePctObj = priceData.get("changePct24h");
                if (changePctObj != null) {
                    try {
                        double changePct = changePctObj instanceof Number 
                            ? ((Number) changePctObj).doubleValue() 
                            : Double.parseDouble(changePctObj.toString());
                        symbol.setPriceChangePct24h(BigDecimal.valueOf(changePct));
                    } catch (Exception e) {
                        System.err.println("[AdminSymbolService] Failed to parse changePct24h for " + symbol.getSymbol() + ": " + e.getMessage());
                    }
                }
                
                // 填充24小时涨跌额
                Object changeObj = priceData.get("change24h");
                if (changeObj != null) {
                    try {
                        double change = changeObj instanceof Number 
                            ? ((Number) changeObj).doubleValue() 
                            : Double.parseDouble(changeObj.toString());
                        symbol.setPriceChange24h(BigDecimal.valueOf(change));
                    } catch (Exception e) {
                        System.err.println("[AdminSymbolService] Failed to parse change24h for " + symbol.getSymbol() + ": " + e.getMessage());
                    }
                }
            }
        }
    }
    
    public TradingSymbol getSymbolDetail(Long id) {
        return symbolRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("币种不存在"));
    }
    
    public TradingSymbol createSymbol(TradingSymbol symbol) {
        if (symbolRepository.findBySymbol(symbol.getSymbol()).isPresent()) {
            throw new IllegalArgumentException("交易对已存在");
        }
        // 确保控盘字段有默认值
        if (symbol.getControlEnabled() == null) {
            symbol.setControlEnabled(false);
        }
        if (symbol.getControlPriceOffset() == null) {
            symbol.setControlPriceOffset(BigDecimal.ZERO);
        }
        // 确保合约设置字段有默认值
        if (symbol.getLotSize() == null) {
            symbol.setLotSize(BigDecimal.valueOf(1000));
        }
        if (symbol.getFeeMultiplier() == null) {
            symbol.setFeeMultiplier(BigDecimal.valueOf(30));
        }
        if (symbol.getLeverage() == null) {
            symbol.setLeverage(BigDecimal.valueOf(10));
        }
        return symbolRepository.save(symbol);
    }
    
    public TradingSymbol updateSymbol(Long id, TradingSymbol symbol) {
        TradingSymbol existing = symbolRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("币种不存在"));
        
        // 检查symbol是否重复（排除自己）
        symbolRepository.findBySymbol(symbol.getSymbol())
            .ifPresent(s -> {
                if (!s.getId().equals(id)) {
                    throw new IllegalArgumentException("交易对已存在");
                }
            });
        
        existing.setSymbol(symbol.getSymbol());
        existing.setBaseCurrency(symbol.getBaseCurrency());
        existing.setQuoteCurrency(symbol.getQuoteCurrency());
        existing.setName(symbol.getName());
        existing.setNameEn(symbol.getNameEn());
        existing.setCategory(symbol.getCategory());
        existing.setIconUrl(symbol.getIconUrl());
        existing.setIsHot(symbol.getIsHot());
        existing.setIsEnabled(symbol.getIsEnabled());
        existing.setSortOrder(symbol.getSortOrder());
        existing.setPricePrecision(symbol.getPricePrecision());
        existing.setVolumePrecision(symbol.getVolumePrecision());
        existing.setMinTradeAmount(symbol.getMinTradeAmount());
        existing.setAlltickSymbol(symbol.getAlltickSymbol());
        // 更新控盘相关配置
        existing.setControlEnabled(symbol.getControlEnabled() != null ? symbol.getControlEnabled() : false);
        existing.setControlPriceOffset(symbol.getControlPriceOffset() != null ? symbol.getControlPriceOffset() : BigDecimal.ZERO);
        // 更新合约设置
        if (symbol.getLotSize() != null) {
            existing.setLotSize(symbol.getLotSize());
        }
        if (symbol.getFeeMultiplier() != null) {
            existing.setFeeMultiplier(symbol.getFeeMultiplier());
        }
        if (symbol.getLeverage() != null) {
            existing.setLeverage(symbol.getLeverage());
        }
        
        return symbolRepository.save(existing);
    }
    
    public void deleteSymbol(Long id) {
        symbolRepository.deleteById(id);
    }
    
    public void toggleHot(Long id) {
        TradingSymbol symbol = symbolRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("币种不存在"));
        symbol.setIsHot(!symbol.getIsHot());
        symbolRepository.save(symbol);
    }
    
    /**
     * 批量设置合约杠杆倍数
     * @param leverage 杠杆倍数
     * @param symbolIds 币种ID列表（如果为空，则按分类设置）
     * @param category 分类（如果symbolIds为空，则按此分类设置）
     */
    public void batchSetLeverage(BigDecimal leverage, List<Long> symbolIds, String category) {
        List<TradingSymbol> symbols;
        
        if (symbolIds != null && !symbolIds.isEmpty()) {
            // 按ID列表设置
            symbols = symbolRepository.findAllById(symbolIds);
        } else if (category != null && !category.isEmpty()) {
            // 按分类设置
            symbols = symbolRepository.findByCategory(category);
        } else {
            // 设置所有币种
            symbols = symbolRepository.findAll();
        }
        
        for (TradingSymbol symbol : symbols) {
            symbol.setLeverage(leverage);
        }
        
        symbolRepository.saveAll(symbols);
    }
}


