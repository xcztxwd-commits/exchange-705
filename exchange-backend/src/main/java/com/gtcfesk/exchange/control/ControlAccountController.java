package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/control/accounts") @RequiredArgsConstructor
public class ControlAccountController {
 private final ControlAccountService service;
 @GetMapping public Object list(@RequestParam(defaultValue="0") int page){return ControlController.ok(service.list(page));}
 @PostMapping public Object create(@RequestBody ControlAccountService.Input input){return ControlController.ok(service.create(input));}
 @PutMapping("/{id}") public Object update(@PathVariable Long id,@RequestBody ControlAccountService.Input input){return ControlController.ok(service.update(id,input));}
}
