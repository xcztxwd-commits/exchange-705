package com.gtcfesk.exchange.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/statistics")
@RequiredArgsConstructor
public class StatisticsController {
    
    private final DashboardService dashboardService;
    
    /**
     * 获取数据统计
     */
    @GetMapping
    public ResponseEntity<?> getStatistics(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        try {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();
            
            System.out.println("[StatisticsController] 开始获取统计数据，日期范围: " + start + " 至 " + end);
            
            // 获取统计数据
            Map<String, Object> stats = dashboardService.getStatisticsData(start, end);
            System.out.println("[StatisticsController] 统计数据: " + stats);
            
            stats.put("startDate", start.toString());
            stats.put("endDate", end.toString());
            
            // 获取充值和提现图表数据（根据日期范围计算）
            System.out.println("[StatisticsController] 开始获取图表数据");
            Map<String, Object> chartData = dashboardService.getDepositWithdrawChartDataByDateRange(start, end, null);
            System.out.println("[StatisticsController] 图表数据获取完成，日期数量: " + (chartData.get("dates") != null ? ((java.util.List<?>) chartData.get("dates")).size() : 0));
            stats.put("chartData", chartData);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", stats);
            
            System.out.println("[StatisticsController] 返回结果: " + result);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("[StatisticsController] 获取统计数据失败: " + e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}

