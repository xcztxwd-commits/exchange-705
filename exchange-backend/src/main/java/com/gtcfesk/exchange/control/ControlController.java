package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.PageRequest;
import java.util.*;
@RestController @RequestMapping("/api/control") @RequiredArgsConstructor
public class ControlController {
 @org.springframework.beans.factory.annotation.Autowired private TenantReadinessService readiness;
 @org.springframework.beans.factory.annotation.Autowired private ControlPolicyDefinitionService definitions;
 @org.springframework.beans.factory.annotation.Autowired private TenantDomainVerification domains;
 private final ControlService service;private final ControlAdminRepository admins;private final TenantManagementService management;
 private final TenantRepository tenants;private final TenantPolicyRepository policies;private final ControlAuditLogRepository audit;private final ControlAccessSessionRepository sessions;
 @PostMapping("/auth/login") public Object login(@RequestBody Login body){return service.login(body.account,body.password,body.totp);}
 @GetMapping("/auth/me") public Object me(){return ok(admins.findById(ControlIdentity.actorId()).orElseThrow(ControlService::invalid));}
 @PostMapping("/auth/logout") public Object logout(){service.logout();return ok(true);}
 @GetMapping("/policy-definitions") public Object definitions(){return ok(definitions.list());}
 @PutMapping("/policy-definitions") public Object definition(@RequestBody ControlPolicyDefinitionService.Input body){return ok(definitions.save(body));}
 @GetMapping("/tenants") public Object tenants(){ControlIdentity.actorId();return ok(tenants.findAll());}
 @PostMapping("/tenants") public Object create(@RequestBody TenantInput body){return ok(management.create(body));}
 @PutMapping("/tenants/{id}") public Object update(@PathVariable Long id,@RequestBody TenantInput body){return ok(management.update(id,body));}
 @PostMapping("/tenants/{id}/verify-domain") public Object verify(@PathVariable Long id){return ok(management.verifyDomain(id));}
 @GetMapping("/tenants/{id}/domains") public Object domains(@PathVariable Long id){return ok(domains.candidates(id));}
 @GetMapping("/tenants/{id}/domains/state") public Object domainState(@PathVariable Long id){return ok(domains.state(id));}
 @PostMapping("/tenants/{id}/domains/prepare-change") public Object prepareChange(@PathVariable Long id,@RequestBody DomainInput body){requireDomainVersion(body);return ok(domains.prepareChange(id,body.entryHost,body.frontendHost,body.domainVersion,body.reason));}
 @PostMapping("/tenants/{id}/domains/activate-change") public Object activateChange(@PathVariable Long id,@RequestBody DomainInput body){requireDomainVersion(body);return ok(domains.activateChange(id,body.domainVersion,body.candidates,body.entryEnabled,body.reason));}
 @PostMapping("/tenants/{id}/domains/entry-switch") public Object entrySwitch(@PathVariable Long id,@RequestBody DomainInput body){requireDomainVersion(body);if(body.entryEnabled==null)throw new IllegalArgumentException("需要入口开关");return ok(domains.setEntryEnabled(id,body.entryEnabled,body.domainVersion,body.reason));}
 private static void requireDomainVersion(DomainInput body){if(body.domainVersion==null||body.domainVersion<0)throw new IllegalArgumentException("需要域名配置版本");}
 @PostMapping("/tenants/{id}/domains/prepare") public Object prepare(@PathVariable Long id,@RequestBody DomainInput body){if(body.role!=null){requireDomainVersion(body);return ok(domains.prepare(id,body.role,body.hostname,body.domainVersion,body.reason));}return ok(domains.prepare(id,body.hostname,body.reason));}
 @PostMapping("/tenants/{id}/domains/verify") public Object verify(@PathVariable Long id,@RequestBody DomainInput body){if(body.version==null)throw new IllegalArgumentException("需要候选版本");if(body.role!=null){requireDomainVersion(body);return ok(domains.verify(id,body.role,body.hostname,body.version,body.domainVersion));}return ok(domains.verify(id,body.hostname,body.version));}
 @PostMapping("/tenants/{id}/domains/activate") public Object activate(@PathVariable Long id,@RequestBody DomainInput body){if(body.version==null)throw new IllegalArgumentException("需要候选版本");return ok(domains.activate(id,body.hostname,body.version,body.reason));}
 @PostMapping("/tenants/{id}/domains/release") public Object release(@PathVariable Long id,@RequestBody DomainInput body){if(body.version==null)throw new IllegalArgumentException("需要退役版本");return ok(body.role==null?domains.release(id,body.hostname,body.version,body.reason):domains.release(id,body.role,body.hostname,body.version,body.reason));}
 @GetMapping("/tenants/{id}/readiness") public Object readiness(@PathVariable Long id){ControlIdentity.actorId();return ok(readiness.report(id));}
 @GetMapping("/tenants/{id}/policies") public Object policies(@PathVariable Long id){return ok(policies.findByTenantId(id).stream().map(p->{Map<String,Object> view=new LinkedHashMap<>();view.put("key",p.getKey());view.put("value",com.gtcfesk.exchange.tenant.TenantSecrets.secret(p.getKey())?com.gtcfesk.exchange.tenant.TenantSecrets.MASK:p.getValue());view.put("locked",p.isLocked());view.put("version",p.getVersion());return view;}).collect(java.util.stream.Collectors.toList()));}
 @PutMapping("/tenants/{id}/policies") public Object policy(@PathVariable Long id,@RequestBody PolicyInput body){return ok(management.policy(id,body));}
 @PostMapping("/tenants/{id}/access-ticket") public Object ticket(@PathVariable Long id,@RequestBody TicketInput body){return service.ticket(id,body.browserBinding);}
 @GetMapping("/access-sessions") public Object sessions(@RequestParam(defaultValue="0") int page){return ok(sessions.findByActorIdOrderByCreatedAtDesc(ControlIdentity.actorId(),PageRequest.of(Math.max(0,page),20)));}
 @PostMapping("/access-sessions/{id}/revoke") public Object revoke(@PathVariable String id){service.revoke(id);return ok(true);}
 @GetMapping("/audit") public Object audit(@RequestParam(required=false) Long tenantId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){ControlIdentity.actorId();PageRequest p=PageRequest.of(Math.max(0,page),Math.max(1,Math.min(100,size)));return ok(tenantId==null?audit.findAllByOrderByIdDesc(p):audit.findByTenantIdOrderByIdDesc(tenantId,p));}
 static Map<String,Object> ok(Object data){Map<String,Object> out=new LinkedHashMap<>();out.put("success",true);out.put("data",data);return out;}
 public static class DomainInput{public String role,hostname,entryHost,frontendHost,reason;public Long version,domainVersion;public Boolean entryEnabled;public List<TenantDomainVerification.Selection> candidates;}
 public static class Login{public String account,password,totp;}
 public static class TicketInput{public String browserBinding;}
 public static class TenantInput{public String code,name,frontendHost,status,reason;public Boolean configReady;}
 public static class PolicyInput{public String key,value,reason;public Boolean locked;}
}
