package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/control/security") @RequiredArgsConstructor
public class ControlSecurityController {
 private final ControlSecurityService service;
 @PostMapping("/mfa") public Object mfa(@RequestBody ControlSecurityService.Input input){service.changeMfa(input);return ControlController.ok(true);}
}
