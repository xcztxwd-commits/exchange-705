package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.config.AdminPermission;
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
import org.springframework.web.util.UriComponentsBuilder;
import java.util.*;

@RestController @RequestMapping("/api/admin/account-inspection") @RequiredArgsConstructor
public class AdminAccountInspectionController {
    private final AccountInspection inspection;
    private final AdminPermissionService permissions;
    private final SimulationEnvironment environment;
    @Value("${simulation.inspection-url:http://demo-backend:8080/api/simulation/inspection}") private String url;
    @Value("${simulation.inspection-key:}") private String key;
    @ModelAttribute
    public void noCache(javax.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    public Map<String,Object> read(String mode,String kind,Long userId,String status,int page,int size) {
        return read(mode,kind,userId,null,status,page,size);
    }
    @GetMapping @AdminPermission(menu="users")
    public Map<String,Object> read(@RequestParam(defaultValue="REAL") String mode,@RequestParam(defaultValue="wallets") String kind,
            @RequestParam(required=false) Long userId,@RequestParam(required=false) String userEmail,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        if(environment.enabled() || SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")||a.getAuthority().equals("ROLE_SUPER_ADMIN"))) throw new AccessDeniedException("仅管理员可查看账户数据");
        String[] spec=AccountInspection.type(kind);permissions.require("users","view");permissions.require(spec[1],"view");
        if(!Arrays.asList("REAL","DEMO").contains(mode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"账户类型无效");
        if("REAL".equals(mode)){Map<String,Object> result=inspection.read(kind,userId,userEmail,status,page,size);result.put("environment","REAL");return result;}
        if(key.length()<32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模拟账户查看服务尚未配置");
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(2000);factory.setReadTimeout(5000);
        HttpHeaders headers=new HttpHeaders();headers.set("X-Simulation-Inspection-Key",key);headers.set("X-Simulation-Tenant-Id",String.valueOf(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()));
        UriComponentsBuilder uri=UriComponentsBuilder.fromHttpUrl(url).queryParam("kind",kind).queryParam("page",page).queryParam("size",size);
        if(userEmail!=null)uri.queryParam("userEmail",userEmail);if(userId!=null)uri.queryParam("userId",userId);if(status!=null)uri.queryParam("status",status);
        try {
            Map<String,Object> result=new RestTemplate(factory).exchange(uri.build().encode().toUri(),HttpMethod.GET,new HttpEntity<>(headers),Map.class).getBody();
            if(result==null || !"DEMO".equals(result.get("environment")) || !(result.get("tenantId") instanceof Number) || ((Number)result.get("tenantId")).longValue()!=com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()) throw new IllegalStateException();
            return result;
        }catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模拟账户查询失败，未回退到真实账户");}
    }
}
