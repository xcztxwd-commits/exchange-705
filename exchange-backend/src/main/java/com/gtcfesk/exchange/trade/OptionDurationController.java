package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.entity.OptionDuration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/trade/option/durations")
@RequiredArgsConstructor
public class OptionDurationController {

    private final OptionDurationService optionDurationService;

    /**
     * 获取启用的期限选项列表（用户端）
     */
    @GetMapping
    public ResponseEntity<?> getEnabledDurations() {
        List<OptionDuration> durations = optionDurationService.getEnabledDurations();
        return ResponseEntity.ok(durations);
    }
}



