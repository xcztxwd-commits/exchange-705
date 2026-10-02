package com.gtcfesk.exchange.tenant;
import com.gtcfesk.exchange.control.TenantPolicyService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.InitializingBean;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;

/** Explicit legacy regression fixture, never production bootstrap or tenant fallback. */
@TestConfiguration
public class BootTenantFixture {
 public static final String ADMIN="admin.mt705.test",CONTROL="control.mt705.test",FRONT="a.mt705.test";
 @Bean @DependsOn("entityManagerFactory") InitializingBean tenantFixture(JdbcTemplate db){return ()->{
  String url;try(java.sql.Connection connection=db.getDataSource().getConnection()){url=connection.getMetaData().getURL();}
  if(!url.startsWith("jdbc:h2:mem:")&&!url.matches("jdbc:mysql://127\\.0\\.0\\.1:33315/mt705_[a-z0-9_]+.*"))throw new IllegalStateException("Explicit isolated test database required");
  for(long id=1;id<=2;id++){
   if(db.queryForObject("SELECT COUNT(*) FROM tenant WHERE id=?",Integer.class,id)==0)db.update("INSERT INTO tenant(id,code,name,frontend_host,status,template_version,policy_version,session_version,config_ready,domain_verified,row_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,"fixture-"+id,"Visible regression fixture "+id,id==1?FRONT:"b.mt705.test","ACTIVE","safe-v1",0,0,true,true,0);
   for(String feature:TenantPolicyService.FEATURES)if(db.queryForObject("SELECT COUNT(*) FROM tenant_policy WHERE tenant_id=? AND policy_key=?",Integer.class,id,"feature."+feature)==0)db.update("INSERT INTO tenant_policy(tenant_id,policy_key,policy_value,locked,version) VALUES(?,?,?,false,0)",id,"feature."+feature,"true");
   for(String[] config:new String[][]{{"site.name","Visible regression fixture"},{"system.timezone","UTC"}})if(db.queryForObject("SELECT COUNT(*) FROM system_config WHERE tenant_id=? AND config_key=?",Integer.class,id,config[0])==0)db.update("INSERT INTO system_config(tenant_id,config_key,config_value,created_at,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,config[0],config[1]);
  }
 };}
 @Bean MockMvcBuilderCustomizer explicitKnownHosts(){return builder->builder.defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").with(request->{
  if(request.getHeader("Host")==null)request.addHeader("Host",request.getRequestURI().startsWith("/api/admin/")?ADMIN:request.getRequestURI().startsWith("/api/control/")?CONTROL:FRONT);return request;
 }));}
 /** Preserve only the surrounding test's explicitly opened scope; real HTTP filters still clear and resolve their own scope. */
 public static class RestoreFixtureScope implements Filter,Ordered {
  public int getOrder(){return Ordered.HIGHEST_PRECEDENCE;}
  public void doFilter(ServletRequest r,ServletResponse s,FilterChain chain)throws java.io.IOException,ServletException{Long fixture=TenantContext.currentTenantId();try{chain.doFilter(r,s);}finally{TenantContext.clear();if(fixture!=null)TenantContext.open(fixture);}}
 }
 @Bean RestoreFixtureScope restoreTestScope(){return new RestoreFixtureScope();}
}
