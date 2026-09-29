package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.market.PersistentPriceControl;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/admin/ai-control")
public class AdminAiControlController {
    @Autowired private ForexQuoteMarketService market;
    @Autowired private PersistentPriceControl controls;

    @Getter @Setter
    public static class StartRequest extends com.gtcfesk.exchange.market.RecoveryOptions {
        @NotNull @Min(1) @Max(86400) @Digits(integer = 5, fraction = 0) private BigDecimal durationSeconds;
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 8) private BigDecimal targetPrice;
        @NotNull @Min(1) @Max(10) @Digits(integer = 2, fraction = 0) private BigDecimal intensity;
        @NotNull private Boolean randomOscillation = true;
        @Size(max = 64) private String requestKey;
    }

    @Getter @Setter
    public static class ManualRequest {
        @NotNull private Boolean enabled;
        @NotNull @Digits(integer = 16, fraction = 16) private BigDecimal offset;
    }

    @Getter @Setter
    public static class RestoreRequest {
        @Size(max = 64) private String requestKey;
        @NotNull @Min(1) @Max(86400) @Digits(integer = 5, fraction = 0) private BigDecimal durationSeconds;
        @NotNull @Min(1) @Max(10) @Digits(integer = 2, fraction = 0) private BigDecimal intensity;
        @NotNull private Boolean randomOscillation = false;
    }

    @Getter @Setter
    public static class RandomMarketRequest {
        @NotNull private Boolean enabled;
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 8) private BigDecimal basePrice;
    }

    @PostMapping("/{id}/random-market")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "random")
    public Map<String, Object> randomMarket(@PathVariable Long id, @Valid @RequestBody RandomMarketRequest request) {
        return market.randomMarket(id, request.getEnabled(), request.getBasePrice());
    }

    @GetMapping("/symbols")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "")
    public List<Map<String, Object>> queryControlSymbols() { return market.controlSymbols(); }

    @GetMapping("/{id}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "")
    public Map<String, Object> getControl(@PathVariable Long id) { return market.controlStatus(id); }

    @GetMapping("/{id}/history")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "")
    public List<PersistentPriceControl.Task> history(@PathVariable Long id, @RequestParam(required = false) Long before) {
        return controls.history(id, before);
    }

    @PostMapping("/{id}/start")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "start")
    public Map<String, Object> startControl(@PathVariable Long id, @Valid @RequestBody StartRequest request) {
        return market.startControl(id, request.getDurationSeconds().intValueExact(), request.getTargetPrice(), request.getIntensity().intValueExact(), request.getRandomOscillation(), request.getRequestKey(), request);
    }
    @PostMapping("/{id}/preview")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "preview")
    public Map<String, Object> previewControl(@PathVariable Long id, @Valid @RequestBody StartRequest request) {
        return market.previewControl(id, request.getDurationSeconds().intValueExact(), request.getTargetPrice(),
                request.getIntensity().intValueExact(), request.getRandomOscillation());
    }

    @PostMapping("/{id}/history/{taskId}/replace")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "replace_history")
    public PersistentPriceControl.Task replaceHistory(@PathVariable Long id, @PathVariable String taskId) {
        market.controlStatus(id); // Use the same symbol validation as other control actions.
        return controls.replaceHistory(id, taskId);
    }

    @PostMapping("/{id}/manual")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "manual")
    public Map<String, Object> manualControl(@PathVariable Long id, @Valid @RequestBody ManualRequest request) {
        return market.manualControl(id, request.getEnabled(), request.getOffset());
    }

    @PostMapping("/{id}/stop")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "stop")
    public Map<String, Object> stopControl(@PathVariable Long id) { return market.stopControl(id); }

    @PostMapping("/{id}/restore")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "restore")
    public Map<String, Object> restoreControl(@PathVariable Long id, @Valid @RequestBody RestoreRequest request) {
        if (request.getRequestKey() != null)
            return market.restoreControl(id, request.getDurationSeconds().intValueExact(), request.getIntensity().intValueExact(), request.getRandomOscillation(), request.getRequestKey());
        return market.restoreControl(id, request.getDurationSeconds().intValueExact(), request.getIntensity().intValueExact(), request.getRandomOscillation());
    }
}
