package com.gtcfesk.exchange.support;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/user/support/unified-inbox") @RequiredArgsConstructor
public class UnifiedInboxController {
    private final UnifiedInboxService service;
    @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="en") String language, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="30") int size) { return service.list(language,page,size); }
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable String id,@RequestParam(defaultValue="en") String language) { return service.detail(id,language); }
    @PostMapping("/{id}/read") public Map<String,Object> read(@PathVariable String id,@RequestParam(defaultValue="en") String language) { return service.read(id,language); }
    @PostMapping("/read-all") public void readAll(@RequestParam(defaultValue="en") String language) { service.readAll(language); }
}
