package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/control/operations")
public class OperationalIssueController {
 private final OperationalIssueService issues;private final TenantRepository tenants;private final ControlAuditService audit;
 @GetMapping("/health") public Object health(){return ControlController.ok(issues.health());}
 @GetMapping("/tenants/{tenant}") public Object list(@PathVariable Long tenant,@RequestParam(defaultValue="0") int page){Long actor=issues.actor();tenants.findById(tenant).orElseThrow(ControlService::invalid);Object rows=issues.list(tenant,page);audit.record(actor,tenant,null,"OPERATIONAL_ISSUES_READ","tenant","SUCCESS","page="+page,null);return ControlController.ok(rows);}
 @PostMapping("/tenants/{tenant}/review") public Object review(@PathVariable Long tenant,@RequestBody Input body){tenants.findById(tenant).orElseThrow(ControlService::invalid);issues.review(tenant,body.job,body.expectedFailureId,body.reason,body.evidenceSha256,body.result);return ControlController.ok(true);}
 public static class Input {public String job,reason,evidenceSha256,result;public long expectedFailureId;@com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("未知复核字段");}}
}
