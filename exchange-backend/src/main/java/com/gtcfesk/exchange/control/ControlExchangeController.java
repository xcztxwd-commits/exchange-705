package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/auth") @RequiredArgsConstructor
public class ControlExchangeController {
 private final ControlService service;private final TenantHostService hosts;
 @GetMapping("/control-exchange-config") public java.util.Map<String,String> config(javax.servlet.http.HttpServletResponse response){
  if(hosts.adminOrigin().isEmpty()||hosts.controlOrigin().isEmpty())throw new IllegalStateException("请先配置平台后台与总控入口");
  response.setHeader("Cache-Control","no-store");java.util.Map<String,String> out=new java.util.LinkedHashMap<>();out.put("adminOrigin",hosts.adminOrigin());out.put("controlOrigin",hosts.controlOrigin());return out;
 }
 @PostMapping("/control-exchange") public Object exchange(@RequestBody Input body){return service.exchange(body.ticket,body.browserBinding,body.expectedTenantId);}
 @PostMapping("/control-activity") public Object activity(){service.activity();return ControlController.ok(true);}
 @PostMapping("/control-exit") public Object exit(){ControlIdentity i=ControlIdentity.current();if(i==null||i.getAccessSessionId()==null)throw ControlService.invalid();service.revoke(i.getAccessSessionId());return ControlController.ok(true);}
 public static class Input{public String ticket,browserBinding;public Long expectedTenantId;}
}
