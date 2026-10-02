package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.admin.dto.SymbolQueryRequest;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.market.*;
import com.gtcfesk.exchange.common.BusinessException;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
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
    
    @Autowired private MarketInstrumentCatalog catalog;
    @Autowired private MarketCategoryService categories;
    @Autowired private ForexQuoteMarketService quotes;
    @Autowired private TransactionTemplate transactions;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;

    public synchronized Map<String,Object> addFromCatalog(String source, String sourceCategory, String projectCategory, List<String> codes) {
        if(codes==null || codes.isEmpty() || codes.size()>20 || new HashSet<>(codes).size()!=codes.size())
            throw new BusinessException("请选择 1–20 个不重复的交易对");
        String category=categories.projectCategory(projectCategory, source, sourceCategory);
        List<TradingSymbol> resolved=new ArrayList<>();
        for(String code:codes) {
            TradingSymbol symbol=catalog.resolve(source,sourceCategory,code);
            symbol.setCategory(category); resolved.add(symbol);
        }
        Map<String,Object> result=transactions.execute(status -> {
            Set<String> keys=new HashSet<>();
            for(TradingSymbol row:symbolRepository.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId())) keys.add(row.getMarketInstrumentKey());
            long registered=symbolRepository.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()).stream().filter(row -> source.equals(row.getMarketSource()) && sourceCategory.equals(row.getSourceCategory())).count();
            long newCount=resolved.stream().filter(row -> !keys.contains(row.getMarketInstrumentKey())).count();
            if(registered+newCount>512) throw new BusinessException("每个源分类最多添加 512 个交易对");
            List<TradingSymbol> subscriptions=new ArrayList<>(symbolRepository.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()));
            subscriptions.addAll(resolved);
            Map<String,Set<String>> channels=new HashMap<>();
            for(TradingSymbol row:subscriptions) {
                channels.computeIfAbsent(row.getSourceCategory(),key->new HashSet<>()).add(row.getSymbol());
                QuoteCurrencyConversion conversion=QuoteCurrencyConversion.route(row.getQuoteCurrency(),row.getMarketSource());
                if(conversion!=null) channels.computeIfAbsent(conversion.category,key->new HashSet<>()).add(conversion.code);
            }
            if(channels.values().stream().anyMatch(items->items.size()>512))
                throw new BusinessException("行情订阅已达上限（含自动汇率），请减少交易对后重试");
            List<String> added=new ArrayList<>(),existing=new ArrayList<>();
            for(TradingSymbol symbol:resolved) {
                if(keys.contains(symbol.getMarketInstrumentKey())) {existing.add(symbol.getSymbol());continue;}
                if(symbolRepository.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), symbol.getSymbol()).isPresent())
                    throw new BusinessException("交易对已被其他源使用："+symbol.getDisplayName());
                createSymbol(symbol); keys.add(symbol.getMarketInstrumentKey()); added.add(symbol.getSymbol());
            }
            Map<String,Object> response=new LinkedHashMap<>();response.put("added",added);response.put("existing",existing);return response;
        });
        quotes.refreshSymbols(); return result;
    }

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
            Map<String, Object> priceData = redisMarketService.getPrice(symbol.getSourceCategory() + ":" + alltickSymbol);
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
        return symbolRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id)
            .orElseThrow(() -> new IllegalArgumentException("币种不存在"));
    }
    
    private TradingSymbol createSymbol(TradingSymbol symbol) {
        com.gtcfesk.exchange.trade.FxContractRules.defaults(symbol);
        if (symbol.getMaxLeverage() == null) symbol.setMaxLeverage(BigDecimal.valueOf(100));
        com.gtcfesk.exchange.common.TradeValidation.leverage(symbol.getMaxLeverage());
        if (symbolRepository.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), symbol.getSymbol()).isPresent()) {
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
        if("Crypto".equals(symbol.getSourceCategory()) || "CryptoPerpetual".equals(symbol.getSourceCategory())) {
            // Unknown assets require explicit reviewed configuration; never copy BTC's step.
            symbol.setIsEnabled(false);
        }
        com.gtcfesk.exchange.trade.QuantityRules.configuration(symbol);
        return symbolRepository.save(symbol);
    }
    
    @org.springframework.transaction.annotation.Transactional
    public TradingSymbol updateSymbol(Long id, TradingSymbol symbol) {
        if (symbol.getMaxLeverage() != null) com.gtcfesk.exchange.common.TradeValidation.leverage(symbol.getMaxLeverage());
        TradingSymbol existing = symbolRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id)
            .orElseThrow(() -> new IllegalArgumentException("币种不存在"));
        
        if(entityManager!=null) entityManager.refresh(existing,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        String previousSpec=com.gtcfesk.exchange.trade.QuantityRules.signature(existing);
        if(existing.getQuantityUnitType()!=null && !Objects.equals(existing.getSpecVersion(),symbol.getSpecVersion()))
            throw new BusinessException("规格版本已变化，请刷新");
        if(existing.getQuantityUnitType()!=null && symbol.getQuantityUnitType()==null)
            throw new BusinessException("不能移除已启用的数量协议");
        if(symbol.getQuantityUnitType()!=null) {
            existing.setQuantityUnitType(symbol.getQuantityUnitType());existing.setMinOrderQuantity(symbol.getMinOrderQuantity());
            existing.setQuantityStep(symbol.getQuantityStep());existing.setMinOrderNotional(symbol.getMinOrderNotional());
        }
        if(!Objects.equals(existing.getSymbol(),symbol.getSymbol()) || !Objects.equals(existing.getAlltickSymbol(),symbol.getAlltickSymbol())
            || !Objects.equals(existing.getMarketSource(),symbol.getMarketSource()) || !Objects.equals(existing.getSourceCategory(),symbol.getSourceCategory()))
            throw new BusinessException("源、源分类和交易对不可修改，请从源目录重新添加");
        if(symbol.getCategory()==null || symbol.getCategory().isEmpty()) throw new BusinessException("请选择项目分类");
        categories.projectCategory(symbol.getCategory(),existing.getMarketSource(),existing.getSourceCategory());
        if(!Boolean.TRUE.equals(existing.getIsEnabled()) && Boolean.TRUE.equals(symbol.getIsEnabled())
            && ("Crypto".equals(existing.getSourceCategory()) || "CryptoPerpetual".equals(existing.getSourceCategory())) && existing.getQuantityUnitType()==null)
            throw new BusinessException("启用加密品种前必须完整配置数量规格");
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

        // 控盘由专用接口修改，避免币种表单的旧值覆盖正在运行的任务。
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
        if (symbol.getMaxLeverage() != null) existing.setMaxLeverage(symbol.getMaxLeverage());
        
        if(!previousSpec.equals(com.gtcfesk.exchange.trade.QuantityRules.signature(existing)) && existing.getQuantityUnitType()!=null)
            existing.setSpecVersion(existing.getSpecVersion()==null?1L:Math.addExact(existing.getSpecVersion(),1L));
        com.gtcfesk.exchange.trade.QuantityRules.configuration(existing);
        com.gtcfesk.exchange.trade.FxContractRules.validate(existing);
        if (com.gtcfesk.exchange.trade.FxContractRules.isForex(existing)) {
            existing.setVolumePrecision(2); existing.setMinTradeAmount(new BigDecimal("0.01"));
        }
        TradingSymbol saved=symbolRepository.saveAndFlush(existing);
        quotes.refreshSymbols();
        return saved;
    }
    
    public void deleteSymbol(Long id) {
        symbolRepository.deleteByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id);
        quotes.refreshSymbols();
    }
    
    public void toggleHot(Long id) {
        TradingSymbol symbol = symbolRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id)
            .orElseThrow(() -> new IllegalArgumentException("币种不存在"));
        symbol.setIsHot(!symbol.getIsHot());
        symbolRepository.save(symbol);
    }
    
    /**
     * 批量设置用户可选杠杆上限
     * @param leverage 用户可选杠杆上限
     * @param symbolIds 币种ID列表（如果为空，则按分类设置）
     * @param category 分类（如果symbolIds为空，则按此分类设置）
     */
    @org.springframework.transaction.annotation.Transactional
    public void batchSetLeverage(BigDecimal leverage, List<Long> symbolIds, String category) {
        com.gtcfesk.exchange.common.TradeValidation.leverage(leverage);
        List<TradingSymbol> symbols;
        
        if (symbolIds != null && !symbolIds.isEmpty()) {
            // 按ID列表设置
            symbols = symbolRepository.findAllByTenantIdAndIdIn(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), symbolIds);
        } else if (category != null && !category.isEmpty()) {
            // 按分类设置
            symbols = symbolRepository.findByTenantIdAndCategory(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), category);
        } else {
            // 设置所有币种
            symbols = symbolRepository.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        }
        
        for (TradingSymbol symbol : symbols) {
            if(entityManager!=null) entityManager.refresh(symbol,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
            if(symbol.getQuantityUnitType()!=null && (symbol.getMaxLeverage()==null || symbol.getMaxLeverage().compareTo(leverage)!=0)) symbol.setSpecVersion(Math.addExact(symbol.getSpecVersion(),1L));
            symbol.setMaxLeverage(leverage);
        }
        
        symbolRepository.saveAll(symbols);
    }
}


