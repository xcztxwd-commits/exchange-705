package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.OperationLog;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/operation-logs")
@RequiredArgsConstructor
public class OperationLogController {
    
    private final OperationLogService operationLogService;
    
    /**
     * 分页查询操作日志
     */
    @GetMapping
    public ResponseEntity<?> getOperationLogs(
            @RequestParam(required = false) Long adminId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            LocalDateTime start = null;
            LocalDateTime end = null;
            
            if (startTime != null && !startTime.isEmpty()) {
                start = LocalDateTime.parse(startTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            if (endTime != null && !endTime.isEmpty()) {
                end = LocalDateTime.parse(endTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            
            Page<OperationLog> logs = operationLogService.getOperationLogs(
                    adminId, operationType, start, end, page, size);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", logs.getContent());
            result.put("total", logs.getTotalElements());
            result.put("page", logs.getNumber());
            result.put("size", logs.getSize());
            result.put("totalPages", logs.getTotalPages());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "查询失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(result);
        }
    }
}

