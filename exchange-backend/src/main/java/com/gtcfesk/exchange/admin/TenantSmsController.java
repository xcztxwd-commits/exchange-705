package com.gtcfesk.exchange.admin;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.gtcfesk.exchange.config.AdminPermission;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

@RestController @RequestMapping("/api/admin/config/sms") @RequiredArgsConstructor
public class TenantSmsController {
 private final TenantSmsService sms;
 public static class Send {public String recipient,purpose;@com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("短信配置测试参数无效");}}
 public static class Consume {public String requestId,recipient,purpose,code;@com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("短信消费参数无效");}}
 @GetMapping("/status") @AdminPermission(menu="settings") public ResponseEntity<?> status(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sms.status());}
 @PostMapping("/test") @AdminPermission(menu="settings",action="save") public ResponseEntity<?> send(@RequestBody Send input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sms.send(input.recipient,input.purpose));}
 @PostMapping("/consume") @AdminPermission(menu="settings",action="save") public ResponseEntity<?> consume(@RequestBody Consume input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sms.consume(input.requestId,input.recipient,input.purpose,input.code));}
}
