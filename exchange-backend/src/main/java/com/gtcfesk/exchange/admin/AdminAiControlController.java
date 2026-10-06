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
    @Autowired private com.gtcfesk.exchange.market.MarketControlCommands commands;

    @Getter @Setter
    public static class StartRequest extends com.gtcfesk.exchange.market.TargetControlOptions {
        @NotNull @Min(1) @Max(86400) @Digits(integer = 5, fraction = 0) private BigDecimal durationSeconds;
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 8) private BigDecimal targetPrice;
        @NotNull @Min(1) @Max(10) @Digits(integer = 2, fraction = 0) private BigDecimal intensity;
        @NotNull private Boolean randomOscillation = true;
        @Pattern(regexp = "[A-Za-z0-9_-]{16,64}") private String requestKey;
    }

    @Getter @Setter
    public static class ManualRequest {
        @Pattern(regexp = "[A-Za-z0-9_-]{16,64}") private String requestKey;
        @NotNull private Boolean enabled;
        @NotNull @Digits(integer = 16, fraction = 16) private BigDecimal offset;
    }

    @Getter @Setter
    public static class RestoreRequest {
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{16,64}") private String requestKey;
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
    public org.springframework.http.ResponseEntity<Map<String,Object>> startControl(@PathVariable Long id, @Valid @RequestBody StartRequest request) {
        org.slf4j.MDC.put("requestKey", request.getRequestKey());
        return org.springframework.http.ResponseEntity.accepted().body(commands.accept(id, request.getDurationSeconds().intValueExact(), request.getTargetPrice(), request.getIntensity().intValueExact(), request.getRandomOscillation(), request.getRequestKey(), request));
    }
    @GetMapping("/{id}/commands")
    @com.gtcfesk.exchange.config.AdminPermission(menu="ai_control",action="")
    public Map<String,Object> command(@PathVariable Long id,@RequestParam String requestKey){return commands.query(id,requestKey);}
    @PostMapping("/{id}/preview")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "preview")
    public Map<String, Object> previewControl(@PathVariable Long id, @Valid @RequestBody StartRequest request) {
        return market.previewControl(id, request.getDurationSeconds().intValueExact(), request.getTargetPrice(),
                request.getIntensity().intValueExact(), request.getRandomOscillation(), request);
    }

    @GetMapping("/{id}/formula")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "")
    public Map<String, Object> formula(@PathVariable Long id) { return market.controlFormula(id); }

    @PutMapping("/{id}/formula")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "start")
    public Map<String, Object> saveFormula(@PathVariable Long id, @Valid @RequestBody StartRequest request) {
        return market.saveControlFormula(id, request.getDurationSeconds().intValueExact(), request.getTargetPrice(), request.getIntensity().intValueExact(), request);
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
        if(request.getRequestKey()!=null)org.slf4j.MDC.put("requestKey", request.getRequestKey());
        return commands.manualControl(id, request.getEnabled(), request.getOffset(), request.getRequestKey());
    }

    @PostMapping("/{id}/stop")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "stop")
    public Map<String, Object> stopControl(@PathVariable Long id,@RequestBody(required=false) Map<String,Object> body) {
        String key=body==null || body.get("requestKey")==null?null:com.gtcfesk.exchange.common.OrderRequest.required(body.get("requestKey"));
        if(key!=null)org.slf4j.MDC.put("requestKey", key);
        return commands.stopControl(id,key);
    }

    @PostMapping("/{id}/restore")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "ai_control", action = "restore")
    public org.springframework.http.ResponseEntity<Map<String,Object>> restoreControl(@PathVariable Long id, @Valid @RequestBody RestoreRequest request) {
        org.slf4j.MDC.put("requestKey", request.getRequestKey());
        return org.springframework.http.ResponseEntity.accepted().body(commands.acceptRestore(id, request.getDurationSeconds().intValueExact(), request.getIntensity().intValueExact(), request.getRandomOscillation(), request.getRequestKey()));
    }
}
