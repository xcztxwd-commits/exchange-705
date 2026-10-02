package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/auth") @RequiredArgsConstructor
public class ControlExchangeController {
 private final ControlService service;
 @PostMapping("/control-exchange") public Object exchange(@RequestBody Input body){return service.exchange(body.ticket,body.browserBinding,body.expectedTenantId);}
 @PostMapping("/control-activity") public Object activity(){service.activity();return ControlController.ok(true);}
 @PostMapping("/control-exit") public Object exit(){ControlIdentity i=ControlIdentity.current();if(i==null||i.getAccessSessionId()==null)throw ControlService.invalid();service.revoke(i.getAccessSessionId());return ControlController.ok(true);}
 public static class Input{public String ticket,browserBinding;public Long expectedTenantId;}
}
