package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.trade.ManualOrderService;
import com.gtcfesk.exchange.trade.ManualOrderGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/orders/contract/manual")
public class ManualOrderController {
    private final ManualOrderService service;
    @GetMapping("/context")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "manual_order")
    public Map<String,Object> context(@RequestParam(required=false) Long userId,@RequestParam(required=false) String search) {return service.context(userId,search);}
    @GetMapping("/minutes")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "manual_order")
    public Map<String,Object> minutes(@RequestParam String symbol,@RequestParam String date,@RequestParam String timezone) {return service.minutes(symbol,date,timezone);}
    @GetMapping("/calendar")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "manual_order")
    public Map<String,Object> calendar(@RequestParam String symbol,@RequestParam String month,@RequestParam String timezone,@RequestParam int page) {return service.calendar(symbol,month,timezone,page);}
    @PostMapping("/preview")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "manual_order")
    public Map<String,Object> preview(@RequestBody ManualOrderService.Request request) {return service.preview(request);}
    @PostMapping("/generate")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "manual_order")
    public Map<String,Object> generate(@RequestBody ManualOrderGenerator.Request request) {return service.generate(request);}
    @PostMapping
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "manual_order")
    public Map<String,Object> create(@RequestBody ManualOrderService.Request request) {return service.create(request);}
}
