package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.FinancialYieldRecord;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.FinancialYieldRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserDashboardService {
    
    private final AssetAccountRepository assetAccountRepository;
    private final FinancialYieldRecordRepository yieldRecordRepository;
    
    /**
     * 获取用户首页统计数据
     */
    public Map<String, Object> getUserDashboardStats(Long userId) {
        Map<String, Object> stats = new HashMap<>();
        
        // 获取用户总资产
        BigDecimal totalAssets = calculateTotalAssets(userId);
        stats.put("totalAssets", totalAssets);
        
        // 计算今日收益（今日已发放的收益）
        LocalDate today = LocalDate.now();
        BigDecimal todayYield = calculateTodayYield(userId, today);
        stats.put("todayYield", todayYield);
        
        // 计算昨日收益（用于计算涨跌幅）
        LocalDate yesterday = today.minusDays(1);
        BigDecimal yesterdayYield = calculateTodayYield(userId, yesterday);
        
        // 计算涨跌幅
        BigDecimal changePct = BigDecimal.ZERO;
        if (yesterdayYield.compareTo(BigDecimal.ZERO) > 0) {
            changePct = todayYield.subtract(yesterdayYield)
                    .divide(yesterdayYield, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        } else if (todayYield.compareTo(BigDecimal.ZERO) > 0) {
            // 如果昨天没有收益，今天有收益，显示100%增长
            changePct = new BigDecimal("100");
        }
        
        stats.put("todayYieldAmount", todayYield);
        stats.put("changePct", changePct);
        
        return stats;
    }
    
    /**
     * 计算用户总资产
     */
    private BigDecimal calculateTotalAssets(Long userId) {
        List<AssetAccount> accounts = assetAccountRepository.findByUserId(userId);
        BigDecimal total = BigDecimal.ZERO;
        
        for (AssetAccount account : accounts) {
            BigDecimal available = account.getAvailable() != null ? account.getAvailable() : BigDecimal.ZERO;
            BigDecimal frozen = account.getFrozen() != null ? account.getFrozen() : BigDecimal.ZERO;
            total = total.add(available).add(frozen);
        }
        
        return total;
    }
    
    /**
     * 计算指定日期的收益（已发放的收益）
     */
    private BigDecimal calculateTodayYield(Long userId, LocalDate date) {
        List<FinancialYieldRecord> records = yieldRecordRepository.findByUserIdOrderByYieldDateDesc(userId);
        
        BigDecimal totalYield = BigDecimal.ZERO;
        for (FinancialYieldRecord record : records) {
            if (record.getYieldDate().equals(date) && "PAID".equals(record.getStatus())) {
                totalYield = totalYield.add(record.getDailyYield());
            }
        }
        
        return totalYield;
    }
}

