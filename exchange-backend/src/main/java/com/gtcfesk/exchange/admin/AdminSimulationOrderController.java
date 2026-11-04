package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.entity.OperationLog;
import com.gtcfesk.exchange.repository.OperationLogRepository;
import com.gtcfesk.exchange.simulation.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController @RequiredArgsConstructor
public class AdminSimulationOrderController {
    private final AdminPermissionService permissions;
    private final SimulationEnvironment environment;
    private final AdminUserRepository admins;
    private final OperationLogRepository logs;
    private final ControlAuditService audit;
    private final ObjectMapper mapper;
    @Value("${simulation.inspection-url:http://demo-backend:8080/api/simulation/inspection}") private String url;
    @Value("${simulation.inspection-key:}") private String key;

    @PostMapping("/api/admin/simulation/orders/{kind:contract|option}/{orderId}/close")
    @com.gtcfesk.exchange.config.AdminPermission(menu="orders",action="close_order")
    public ResponseEntity<?> close(@PathVariable String kind,@PathVariable Long orderId,@RequestBody ControlledExitService.Input input) {
        org.springframework.security.core.Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        if(environment.enabled() || auth==null || !auth.isAuthenticated()
                || auth.getAuthorities().stream().noneMatch(role -> Arrays.asList("ROLE_ADMIN","ROLE_SUPER_ADMIN").contains(role.getAuthority()))
                || auth.getAuthorities().stream().anyMatch(role -> "ROLE_AGENT".equals(role.getAuthority())))
            throw new AccessDeniedException("模拟订单平仓仅对管理员开放");
        permissions.require("orders","view");permissions.require("orders","close_order");
        Long tenant=TenantContext.requireTenantId();ControlIdentity control=ControlIdentity.current();
        SimulationOrderCloseBoundary.Command command=new SimulationOrderCloseBoundary.Command();
        command.kind=kind;command.orderId=orderId;
        if(input==null || input.stopLoss!=null || input.takeProfit!=null)throw new IllegalArgumentException("模拟平仓参数无效");
        command.requestId=input.requestId;command.reason=input.reason;
        if(control!=null) {
            if(!tenant.equals(control.getTenantId()) || control.getAccessSessionId()==null || control.getActorId()==null || control.getActorId()<=0)
                throw new AccessDeniedException("目标租户身份不匹配");
            command.actorType="CONTROL";command.actorId=control.getActorId();command.actorEmail="control#"+command.actorId;
        } else {
            Long actor;try{actor=Long.valueOf(auth.getName());}catch(NumberFormatException invalid){throw new AccessDeniedException("操作者无效");}
            AdminUser admin=admins.findByTenantIdAndId(tenant,actor).filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                    .orElseThrow(() -> new AccessDeniedException("操作者已停用"));
            command.actorType="ADMIN";command.actorId=actor;command.actorEmail=admin.getEmail()==null?null:admin.getEmail().substring(0,Math.min(100,admin.getEmail().length()));
        }
        command.validate();
        if(key.length()<32)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模拟服务尚未配置");
        HttpHeaders headers=new HttpHeaders();headers.set("X-Simulation-Inspection-Key",key);headers.set("X-Simulation-Tenant-Id",tenant.toString());
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(2000);factory.setReadTimeout(10000);
        try {
            ResponseEntity<byte[]> response=new RestTemplate(factory).exchange(url.replaceFirst("/inspection$","/order-close"),
                    HttpMethod.POST,new HttpEntity<>(command,headers),byte[].class);
            Map<String,Object> result=mapper.readerFor(Map.class).with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(response.getBody());
            if(!"DEMO".equals(response.getHeaders().getFirst("X-Account-Environment")))throw new IllegalStateException();
            if(result==null || !Boolean.TRUE.equals(result.get("success")) || !"DEMO".equals(result.get("accountMode"))
                    || !numberEquals(result.get("tenantId"),tenant) || !numberEquals(result.get("orderId"),orderId)
                    || !kind.equals(result.get("kind")) || !command.requestId.equals(result.get("requestId")))throw new IllegalStateException();
            String detail=mapper.writeValueAsString(result);
            if(control!=null)audit.record(control.getActorId(),tenant,control.getAccessSessionId(),"SIMULATION_ORDER_CLOSE",kind+":"+orderId,"SUCCESS",detail,input.reason);
            else {
                OperationLog log=new OperationLog();log.setAdminId(command.actorId);log.setAdminEmail(command.actorEmail);
                log.setOperationType("模拟订单平仓");log.setOperationAction(kind+":close");log.setTargetType("DEMO:"+kind);log.setTargetId(orderId);
                log.setTargetInfo("模拟账户；requestId="+command.requestId);log.setRequestMethod("POST");
                log.setRequestUrl("/api/admin/simulation/orders/"+kind+"/"+orderId+"/close");log.setRequestParams(detail);logs.saveAndFlush(log);
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }catch(HttpStatusCodeException business) {
            int status=business.getRawStatusCode();
            if(Arrays.asList(400,404,409,425).contains(status)) {
                try { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapper.readValue(business.getResponseBodyAsByteArray(),Map.class)); }
                catch(java.io.IOException invalid){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模拟平仓服务响应无效，请保留原请求重试");}
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模拟平仓服务暂不可用，请保留原请求重试");
        }catch(Exception failed){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模拟平仓结果待确认，请保留原请求重试");}
    }
    private boolean numberEquals(Object value,Long expected) { return value instanceof Number && ((Number)value).longValue()==expected; }
}
