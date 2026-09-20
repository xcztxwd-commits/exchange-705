package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.market.ForexQuoteMarketService;
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

    @Getter @Setter
    public static class StartRequest {
        @NotNull @Min(1) @Max(86400) @Digits(integer = 5, fraction = 0) private BigDecimal durationSeconds;
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 8) private BigDecimal targetPrice;
        @NotNull @Min(1) @Max(10) @Digits(integer = 2, fraction = 0) private BigDecimal intensity;
        @NotNull private Boolean randomOscillation = false;
    }

    @Getter @Setter
    public static class ManualRequest {
        @NotNull private Boolean enabled;
        @NotNull @Digits(integer = 16, fraction = 16) private BigDecimal offset;
    }

    @Getter @Setter
    public static class RestoreRequest {
        @NotNull @Min(1) @Max(86400) @Digits(integer = 5, fraction = 0) private BigDecimal durationSeconds;
        @NotNull @Min(1) @Max(10) @Digits(integer = 2, fraction = 0) private BigDecimal intensity;
        @NotNull private Boolean randomOscillation = false;
    }

    @GetMapping("/symbols")
    public List<Map<String, Object>> queryControlSymbols() { return market.controlSymbols(); }

    @GetMapping("/{id}")
    public Map<String, Object> getControl(@PathVariable Long id) { return market.controlStatus(id); }

    @PostMapping("/{id}/start")
    public Map<String, Object> startControl(@PathVariable Long id, @Valid @RequestBody StartRequest request) {
        return market.startControl(id, request.getDurationSeconds().intValueExact(), request.getTargetPrice(), request.getIntensity().intValueExact(), request.getRandomOscillation());
    }

    @PostMapping("/{id}/manual")
    public Map<String, Object> manualControl(@PathVariable Long id, @Valid @RequestBody ManualRequest request) {
        return market.manualControl(id, request.getEnabled(), request.getOffset());
    }

    @PostMapping("/{id}/stop")
    public Map<String, Object> stopControl(@PathVariable Long id) { return market.stopControl(id); }

    @PostMapping("/{id}/restore")
    public Map<String, Object> restoreControl(@PathVariable Long id, @Valid @RequestBody RestoreRequest request) {
        return market.restoreControl(id, request.getDurationSeconds().intValueExact(), request.getIntensity().intValueExact(), request.getRandomOscillation());
    }
}
