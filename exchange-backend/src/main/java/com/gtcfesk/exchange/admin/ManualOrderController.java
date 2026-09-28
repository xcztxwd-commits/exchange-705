package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.trade.ManualOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/orders/contract/manual")
public class ManualOrderController {
    private final ManualOrderService service;
    @GetMapping("/context")
    public Map<String,Object> context(@RequestParam(required=false) Long userId,@RequestParam(required=false) String search) {return service.context(userId,search);}
    @PostMapping("/preview")
    public Map<String,Object> preview(@RequestBody ManualOrderService.Request request) {return service.preview(request);}
    @PostMapping
    public Map<String,Object> create(@RequestBody ManualOrderService.Request request) {return service.create(request);}
}
