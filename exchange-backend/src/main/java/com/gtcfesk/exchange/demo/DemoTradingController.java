package com.gtcfesk.exchange.demo;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoTradingController {
    private final DemoTradingService service;
    public static class Operation {
        @NotNull @Pattern(regexp = "[a-fA-F0-9\\-]{36}") public String requestKey;
        @Min(1) public int generation;
    }
    public static class Buy extends Operation {
        @NotBlank @Size(max = 32) public String symbol;
        @NotNull @DecimalMin("10") @DecimalMax("100000") @Digits(integer = 6, fraction = 2) public BigDecimal amount;
    }
    public static class Close { @Min(1) public int generation; }
    @PostMapping("/account") public Object initialize(Authentication auth) { return service.initialize(Long.valueOf(auth.getName())); }
    @GetMapping("/account") public Object account(Authentication auth) { return service.snapshot(Long.valueOf(auth.getName())); }
    @GetMapping("/instruments") public Object instruments(Authentication auth) { return service.instruments(Long.valueOf(auth.getName())); }
    @PostMapping("/orders") public Object buy(Authentication auth, @Valid @RequestBody Buy body) {
        return service.buy(Long.valueOf(auth.getName()), body.requestKey, body.generation, body.symbol, body.amount);
    }
    @PostMapping("/orders/{id}/close") public Object close(Authentication auth, @PathVariable String id, @Valid @RequestBody Close body) {
        return service.close(Long.valueOf(auth.getName()), id, body.generation);
    }
    @PostMapping("/reset") public Object reset(Authentication auth, @Valid @RequestBody Operation body) {
        return service.reset(Long.valueOf(auth.getName()), body.requestKey, body.generation);
    }
    @GetMapping("/orders") public Object orders(Authentication auth, @RequestParam(defaultValue = "0") int page) {
        return service.history(Long.valueOf(auth.getName()), page);
    }
    @GetMapping("/ledger") public Object ledger(Authentication auth, @RequestParam(defaultValue = "0") int page) {
        return service.ledger(Long.valueOf(auth.getName()), page);
    }
}
