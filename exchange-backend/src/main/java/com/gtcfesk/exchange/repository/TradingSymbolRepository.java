package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.TradingSymbol;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradingSymbolRepository extends JpaRepository<TradingSymbol, Long> {
    
    Optional<TradingSymbol> findBySymbol(String symbol);
    
    List<TradingSymbol> findByIsEnabledTrueOrderBySortOrderDesc();
    
    List<TradingSymbol> findByIsHotTrueAndIsEnabledTrueOrderBySortOrderDesc();
    
    List<TradingSymbol> findByCategoryAndIsEnabledTrueOrderBySortOrderDesc(String category);

    Page<TradingSymbol> findByCategoryAndIsEnabledTrueOrderBySortOrderDesc(String category, Pageable pageable);
    
    List<TradingSymbol> findByCategory(String category);
    
    // 后台管理系统查询：返回所有币种（包括未启用的），以便管理员可以编辑和重新启用
    @Query("SELECT s FROM TradingSymbol s WHERE (s.category = :category OR :category IS NULL OR :category = '') ORDER BY s.sortOrder DESC, s.symbol ASC")
    Page<TradingSymbol> searchSymbols(@Param("category") String category, Pageable pageable);
    
    List<TradingSymbol> findBySymbolIn(List<String> symbols);
    
    List<TradingSymbol> findByAlltickSymbolIn(List<String> alltickSymbols);
    
    // 搜索币种/合约（按symbol、name、nameEn模糊查询）
    @Query("SELECT s FROM TradingSymbol s WHERE s.isEnabled = true AND " +
           "(LOWER(s.symbol) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(s.nameEn) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY s.sortOrder DESC, s.symbol ASC")
    List<TradingSymbol> searchSymbolsByKeyword(@Param("keyword") String keyword);
}

