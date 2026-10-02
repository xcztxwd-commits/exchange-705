package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/control/tenants/{tenant}/retention")
public class ChatRetentionController {
 private final ChatRetentionService retention;
 @GetMapping public Object preview(@PathVariable Long tenant){return ControlController.ok(retention.preview(tenant));}
 @PutMapping public Object policy(@PathVariable Long tenant,@RequestBody Input body){retention.configure(tenant,body.enabled,body.confirm,body.policyVersion,body.previewHash,body.reason);return ControlController.ok(retention.preview(tenant));}
 @PostMapping("/hold/{conversation}") public Object hold(@PathVariable Long tenant,@PathVariable Long conversation,@RequestBody Input body){retention.hold(tenant,conversation,body.enabled,body.reason);return ControlController.ok(true);}
 @PostMapping("/clean") public Object clean(@PathVariable Long tenant,@RequestBody Input body){if(!body.confirm)throw new IllegalArgumentException("必须明确确认清理");return ControlController.ok(retention.clean(tenant,body.policyVersion,body.previewHash,body.reason));}
 public static class Input{public boolean enabled,confirm;public long policyVersion;public String previewHash,reason;}
}
