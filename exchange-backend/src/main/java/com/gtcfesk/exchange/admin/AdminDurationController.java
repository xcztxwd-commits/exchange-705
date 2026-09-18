package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.OptionDuration;
import com.gtcfesk.exchange.repository.OptionDurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/durations")
@RequiredArgsConstructor
public class AdminDurationController {

    private final OptionDurationRepository optionDurationRepository;

    /**
     * 获取所有期限选项列表
     */
    @GetMapping
    public ResponseEntity<?> getAllDurations() {
        List<OptionDuration> durations = optionDurationRepository.findAllByOrderBySortOrderAsc();
        Map<String, Object> result = new HashMap<>();
        result.put("list", durations);
        return ResponseEntity.ok(result);
    }

    /**
     * 创建期限选项
     */
    @PostMapping
    public ResponseEntity<?> createDuration(@RequestBody OptionDuration duration) {
        if (duration.getDuration() == null || duration.getDuration() <= 0) {
            throw new BusinessException("期限必须大于0");
        }
        if (duration.getLabel() == null || duration.getLabel().trim().isEmpty()) {
            throw new BusinessException("标签不能为空");
        }
        
        // 检查是否已存在相同的duration
        List<OptionDuration> existing = optionDurationRepository.findAll();
        boolean duplicate = existing.stream()
            .anyMatch(d -> d.getDuration().equals(duration.getDuration()));
        if (duplicate) {
            throw new BusinessException("该期限已存在");
        }

        if (duration.getSortOrder() == null) {
            // 如果没有指定排序，设置为最大值+1
            int maxOrder = existing.stream()
                .mapToInt(d -> d.getSortOrder() != null ? d.getSortOrder() : 0)
                .max()
                .orElse(0);
            duration.setSortOrder(maxOrder + 1);
        }

        if (duration.getEnabled() == null) {
            duration.setEnabled(true);
        }

        if (duration.getProfitRate() == null) {
            duration.setProfitRate(new java.math.BigDecimal("0.8")); // 默认80%
        }

        if (duration.getLossRate() == null) {
            duration.setLossRate(new java.math.BigDecimal("1.0")); // 默认100%（全部亏损）
        }

        if (duration.getMinAmount() == null) {
            duration.setMinAmount(new java.math.BigDecimal("20.00")); // 默认最低20
        }

        if (duration.getMaxAmount() == null) {
            duration.setMaxAmount(new java.math.BigDecimal("10000.00")); // 默认最大10000
        }

        OptionDuration saved = optionDurationRepository.save(duration);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", saved);
        return ResponseEntity.ok(result);
    }

    /**
     * 更新期限选项
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateDuration(@PathVariable Long id, @RequestBody OptionDuration duration) {
        OptionDuration existing = optionDurationRepository.findById(id)
            .orElseThrow(() -> new BusinessException("期限选项不存在"));

        if (duration.getDuration() != null && duration.getDuration() > 0) {
            // 检查是否与其他选项重复
            List<OptionDuration> all = optionDurationRepository.findAll();
            boolean duplicate = all.stream()
                .filter(d -> !d.getId().equals(id))
                .anyMatch(d -> d.getDuration().equals(duration.getDuration()));
            if (duplicate) {
                throw new BusinessException("该期限已存在");
            }
            existing.setDuration(duration.getDuration());
        }

        if (duration.getLabel() != null && !duration.getLabel().trim().isEmpty()) {
            existing.setLabel(duration.getLabel());
        }

        if (duration.getSortOrder() != null) {
            existing.setSortOrder(duration.getSortOrder());
        }

        if (duration.getEnabled() != null) {
            existing.setEnabled(duration.getEnabled());
        }

        if (duration.getProfitRate() != null) {
            existing.setProfitRate(duration.getProfitRate());
        }

        if (duration.getLossRate() != null) {
            existing.setLossRate(duration.getLossRate());
        }

        if (duration.getMinAmount() != null) {
            existing.setMinAmount(duration.getMinAmount());
        }

        if (duration.getMaxAmount() != null) {
            existing.setMaxAmount(duration.getMaxAmount());
        }

        OptionDuration saved = optionDurationRepository.save(existing);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", saved);
        return ResponseEntity.ok(result);
    }

    /**
     * 删除期限选项
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDuration(@PathVariable Long id) {
        OptionDuration duration = optionDurationRepository.findById(id)
            .orElseThrow(() -> new BusinessException("期限选项不存在"));

        optionDurationRepository.delete(duration);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return ResponseEntity.ok(result);
    }
}

