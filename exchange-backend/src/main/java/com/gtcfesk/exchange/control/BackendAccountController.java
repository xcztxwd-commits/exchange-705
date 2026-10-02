package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.gtcfesk.exchange.admin.AdminPermissionService;
import com.gtcfesk.exchange.tenant.TenantContext;
@RestController @RequiredArgsConstructor
public class BackendAccountController {
 private final BackendAccountService service;private final AdminPermissionService permissions;private final TenantRepository tenants;
 @GetMapping("/api/control/tenants/{id}/backend-accounts") public Object controlList(@PathVariable Long id,@RequestParam(required=false) String userEmail){ControlIdentity.actorId();tenants.findById(id).orElseThrow(ControlService::invalid);return ControlController.ok(service.list(id,userEmail));}
 @PostMapping("/api/control/tenants/{id}/backend-accounts") public Object controlCreate(@PathVariable Long id,@RequestBody BackendAccountService.Input input){ControlIdentity.actorId();tenants.findById(id).orElseThrow(ControlService::invalid);return ControlController.ok(service.create(id,input));}
 @GetMapping("/api/admin/backend-accounts") @com.gtcfesk.exchange.config.AdminPermission(menu="admin_list",action="") public Object tenantList(@RequestParam(required=false) String userEmail){requireSuper();return ControlController.ok(service.list(TenantContext.requireTenantId(),userEmail));}
 @PostMapping("/api/admin/backend-accounts") @com.gtcfesk.exchange.config.AdminPermission(menu="admin_list",action="create") public Object tenantCreate(@RequestBody BackendAccountService.Input input){requireSuper();return ControlController.ok(service.create(TenantContext.requireTenantId(),input));}
 private void requireSuper(){if(!permissions.isSuper())throw new org.springframework.security.access.AccessDeniedException("仅租户负责人可开通后台账号");}
}
