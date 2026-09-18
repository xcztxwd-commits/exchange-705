package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.OperationLog;
import com.gtcfesk.exchange.repository.OperationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OperationLogService {
    
    private final OperationLogRepository operationLogRepository;
    
    /**
     * 记录操作日志
     */
    public void logOperation(OperationLog log) {
        operationLogRepository.save(log);
    }
    
    /**
     * 分页查询操作日志
     */
    public Page<OperationLog> getOperationLogs(
            Long adminId,
            String operationType,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int page,
            int size) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        if (adminId == null && operationType == null && startTime == null && endTime == null) {
            return operationLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        
        return operationLogRepository.findByConditions(adminId, operationType, startTime, endTime, pageable);
    }
    
    /**
     * 获取操作类型统计
     */
    public Map<String, Long> getOperationTypeStats(LocalDateTime startTime, LocalDateTime endTime) {
        // 这里可以添加统计逻辑，暂时返回空Map
        return new HashMap<>();
    }
}




