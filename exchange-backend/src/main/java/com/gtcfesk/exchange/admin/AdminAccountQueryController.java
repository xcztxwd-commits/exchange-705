package com.gtcfesk.exchange.admin;
import com.gtcfesk.exchange.simulation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController @RequiredArgsConstructor
public class AdminAccountQueryController {
    private final AdminPermissionService permissions;
    private final SimulationEnvironment environment;
    @Value("${simulation.inspection-url:http://demo-backend:8080/api/simulation/inspection}") private String url;
    @Value("${simulation.inspection-key:}") private String key;
    @PostMapping("/api/admin/account-query")
    public Object query(@RequestBody AdminReadRoutes.Query query, javax.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        org.springframework.security.core.Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        if(environment.enabled() || auth==null || auth.getAuthorities().stream().noneMatch(a->Arrays.asList("ROLE_ADMIN","ROLE_SUPER_ADMIN").contains(a.getAuthority()))) throw new AccessDeniedException("模拟数据查询仅对管理员开放");
        if(query.body == null || query.params == null) throw new IllegalArgumentException("查询参数不能为空");
        String[] grant=AdminReadRoutes.permission(query.method,query.path).split(":",2);
        if (("/api/admin/users".equals(query.path) || "/api/admin/users/query".equals(query.path)) && "agent".equals(("POST".equals(query.method)?query.body:query.params).get("userType"))) grant=new String[]{"agents","view"};
        permissions.require(grant[0],grant.length==2?grant[1]:"view");
        if ("deposit_orders".equals(grant[0])) permissions.require("deposit_orders","view_deposit_orders");
        if ("inbox".equals(grant[0]) && auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_SUPER_ADMIN"))) throw new AccessDeniedException("跨环境站内信审计仅对超级管理员开放");
        if(key.length()<32)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模拟服务尚未配置");
        SimpleClientHttpRequestFactory f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(2000);f.setReadTimeout(10000);
        HttpHeaders headers=new HttpHeaders();headers.set("X-Simulation-Inspection-Key",key);headers.set("X-Simulation-Tenant-Id",String.valueOf(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()));
        try {
            ResponseEntity<byte[]> result=new RestTemplate(f).exchange(url.replaceFirst("/inspection$","/admin-query"),HttpMethod.POST,new HttpEntity<>(query,headers),byte[].class);
            if(!"DEMO".equals(result.getHeaders().getFirst("X-Account-Environment")))throw new IllegalStateException();
            return ResponseEntity.ok().contentType(result.getHeaders().getContentType()==null?MediaType.APPLICATION_JSON:result.getHeaders().getContentType()).header("Cache-Control","no-store").body(result.getBody());
        }catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模拟查询失败；未回退到真实账户");}
    }
}
