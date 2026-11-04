package com.gtcfesk.exchange.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.ControlledExitService;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A single delegated close, never a general administrator session or a writable query proxy. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 6) @RequiredArgsConstructor
public class SimulationOrderCloseBoundary extends OncePerRequestFilter {
    private final SimulationEnvironment environment;
    private final ControlledExitService exits;
    private final ObjectMapper mapper;
    @Value("${simulation.inspection-key:}") private String key;
    private static final ThreadLocal<Proof> CURRENT = new ThreadLocal<>();

    public static final class Proof {
        public final Long tenantId, orderId, actorId;
        public final String kind, actorType, actorEmail, requestId;
        private Proof(Long tenant, Command command) {
            tenantId=tenant;orderId=command.orderId;actorId=command.actorId;kind=command.kind;
            actorType=command.actorType;actorEmail=command.actorEmail;requestId=command.requestId;
        }
        public void require(Long tenant, String type, Long id, String action, ControlledExitService.Input input) {
            if (!tenantId.equals(tenant) || !orderId.equals(id) || !kind.equals(type) || !"close".equals(action)
                    || input == null || !requestId.equals(input.requestId) || input.stopLoss != null || input.takeProfit != null)
                throw new AccessDeniedException("模拟平仓授权与订单不匹配");
        }
    }
    public static Proof current() { return CURRENT.get(); }

    public static class Command {
        public Long orderId,actorId;
        public String kind,actorType,actorEmail,requestId,reason;
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value) {
            throw new IllegalArgumentException("模拟平仓不接受额外字段: "+name);
        }
        public void validate() {
            if (orderId==null || orderId<=0 || actorId==null || actorId<=0
                    || !Arrays.asList("contract","option").contains(kind) || !Arrays.asList("ADMIN","CONTROL").contains(actorType)
                    || requestId==null || !requestId.matches("[a-zA-Z0-9_-]{16,64}")
                    || reason!=null && reason.length()>500 || actorEmail!=null && actorEmail.length()>100)
                throw new IllegalArgumentException("模拟平仓参数无效");
        }
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/simulation/order-close".equals(request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException {
        response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Account-Environment","DEMO");
        String supplied=request.getHeader("X-Simulation-Inspection-Key");
        if(!environment.enabled() || key.length()<32 || supplied==null
                || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
            fail(response,403,"模拟平仓服务身份无效");return;
        }
        if(!"POST".equals(request.getMethod())) { fail(response,405,"模拟平仓仅接受 POST");return; }
        Long tenant;Command command;
        try {
            tenant=Long.valueOf(request.getHeader("X-Simulation-Tenant-Id"));if(tenant<=0)throw new IllegalArgumentException();
            command=mapper.readValue(request.getInputStream(),Command.class);command.validate();
        }catch(Exception invalid){fail(response,400,"模拟平仓参数无效");return;}
        org.springframework.security.core.context.SecurityContext previous=SecurityContextHolder.getContext();
        Proof prior=CURRENT.get();
        try(TenantContext.Scope ignored=TenantContext.open(tenant)) {
            org.springframework.security.core.context.SecurityContext context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken("simulation-admin-"+command.actorId,null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            SecurityContextHolder.setContext(context);CURRENT.set(new Proof(tenant,command));
            ControlledExitService.Input input=new ControlledExitService.Input();input.requestId=command.requestId;input.reason=command.reason;
            Map<String,Object> result=exits.execute(command.kind,command.orderId,"close",input);
            Map<String,Object> body=new LinkedHashMap<>();body.put("success",true);body.put("accountMode","DEMO");
            body.put("tenantId",tenant);body.put("kind",command.kind);body.put("orderId",command.orderId);body.put("requestId",command.requestId);
            body.put("message","模拟订单平仓成功");body.put("order",result);mapper.writeValue(response.getWriter(),body);
        }catch(AccessDeniedException denied){fail(response,403,"无权执行模拟平仓");}
        catch(BusinessException business){fail(response,400,com.gtcfesk.exchange.common.SafeErrors.message(business));}
        catch(IllegalArgumentException invalid){fail(response,400,"模拟平仓参数无效");}
        catch(org.springframework.web.server.ResponseStatusException conflict){fail(response,conflict.getRawStatusCode(),conflict.getReason());}
        catch(RuntimeException failed){fail(response,500,"模拟平仓失败，请保留原请求重试");}
        finally {if(prior==null)CURRENT.remove();else CURRENT.set(prior);SecurityContextHolder.setContext(previous);}
    }
    private void fail(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);Map<String,Object> body=new LinkedHashMap<>();body.put("success",false);body.put("accountMode","DEMO");body.put("message",message);
        mapper.writeValue(response.getWriter(),body);
    }
}
