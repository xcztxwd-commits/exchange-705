package com.gtcfesk.exchange.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.sql.DataSource;
import javax.persistence.EntityManagerFactory;
import java.util.*;
import java.nio.file.*;

/** Real application service/transaction tests, strictly limited to a new synthetic MySQL fixture. */
public class ChatRetentionMySqlProbe {
 static DataSource source; static String directory; static boolean rejectAudit; static int checks;
 @Configuration @EnableTransactionManagement
 @EnableJpaRepositories(basePackages="com.gtcfesk.exchange",repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
 static class Config {
  @Bean DataSource dataSource(){return source;}
  @Bean JdbcTemplate jdbc(){return new JdbcTemplate(source);}
  @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(){
   LocalContainerEntityManagerFactoryBean b=new LocalContainerEntityManagerFactoryBean();b.setDataSource(source);b.setPackagesToScan("com.gtcfesk.exchange");b.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
   Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","validate");p.setProperty("hibernate.dialect","org.hibernate.dialect.MySQL57Dialect");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");b.setJpaProperties(p);return b;
  }
  @Bean JpaTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
  @Bean ControlAuditService audit(ControlAuditLogRepository logs){return new ControlAuditService(logs){@Override public void record(Long a,Long t,String s,String action,String o,String result,String d,String r){if(rejectAudit&&action.startsWith("CHAT_RETENTION_"))throw new IllegalStateException("Synthetic audit failure");super.record(a,t,s,action,o,result,d,r);}};}
  @Bean ChatRetentionService retention(JdbcTemplate j,TenantRepository t,TenantPolicyRepository p,ControlAuditService a,ObjectMapper m)throws Exception{
   ChatRetentionService result=new ChatRetentionService(j,t,p,a,m);java.lang.reflect.Field f=ChatRetentionService.class.getDeclaredField("archiveDirectory");f.setAccessible(true);f.set(result,directory);return result;
  }
 }
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
 static void denied(Runnable r,String label){try{r.run();}catch(RuntimeException expected){checks++;return;}throw new AssertionError(label);}
 static long number(Map<String,Object> map,String key){return ((Number)map.get(key)).longValue();}
 static void seed(JdbcTemplate j,long tenant,long user,long id,boolean hold){
  j.update("INSERT INTO support_conversation(id,tenant_id,user_id,status,client_ip,created_at,closed_at,updated_at,legal_hold) VALUES(?,?,?,'CLOSED','127.0.0.1',DATE_SUB(UTC_TIMESTAMP(),INTERVAL 400 DAY),DATE_SUB(UTC_TIMESTAMP(),INTERVAL 399 DAY),UTC_TIMESTAMP(),?)",id,tenant,user,hold);
  String hash=com.gtcfesk.exchange.support.SupportService.sha256(new byte[]{1,2,3});
  j.update("INSERT INTO support_message(id,tenant_id,conversation_id,sender,sender_id,sender_name,request_id,text,image,image_hash,created_at,previous_hash,hash) VALUES(?,?,?,'USER',?,'fixture','retention-probe','synthetic evidence',1,?,UTC_TIMESTAMP(),'','synthetic')",id,tenant,id,user,hash);
  j.update("INSERT INTO support_attachment(tenant_id,message_id,content) VALUES(?,?,?)",tenant,id,new byte[]{1,2,3});
 }
 @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception{
  ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
  Map<String,String> c=new ObjectMapper().readValue(Paths.get(args[0]).toFile(),Map.class);
  if(!c.get("url").matches("jdbc:mysql://127\\.0\\.0\\.1:64029/mt705_probe_[a-zA-Z0-9_]+\\?.*"))throw new IllegalArgumentException("Dedicated synthetic MySQL only");
  source=new DriverManagerDataSource(c.get("url"),c.get("username"),c.get("password"));directory=Paths.get(args[1]).toAbsolutePath().toString();System.setProperty("control.retention.archive-directory",directory);
  try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext(Config.class)){
   JdbcTemplate j=context.getBean(JdbcTemplate.class);ChatRetentionService service=context.getBean(ChatRetentionService.class);
   seed(j,2,7000010,990101,false);seed(j,2,7000010,990102,true);seed(j,3,7000011,990201,false);
   UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("control-test",null,Collections.emptyList());auth.setDetails(new ControlIdentity(990001L,null,null));SecurityContextHolder.getContext().setAuthentication(auth);
   check(service.cleanAutomatically(2L)==0,"Default disabled must not delete");
   Map<String,Object> preview=service.preview(2L);check(((List<?>)preview.get("candidateIds")).equals(Arrays.asList(990101L)),"Tenant and legal hold scope");
   long version=number(preview,"policyVersion");String hash=(String)preview.get("previewHash");
   denied(()->service.configure(2L,true,false,version,hash,"Synthetic enable"),"Explicit confirmation required");
   denied(()->service.configure(2L,true,true,version+1,hash,"Synthetic stale"),"Stale version rejected");
   service.configure(2L,true,true,version,hash,"Synthetic enable");
   rejectAudit=true;denied(()->service.cleanAutomatically(2L),"Audit failure must abort transaction");rejectAudit=false;
   check(j.queryForObject("SELECT COUNT(*) FROM support_conversation WHERE tenant_id=2 AND id=990101",Integer.class)==1,"Audit failure restores conversation");
   check(j.queryForObject("SELECT COUNT(*) FROM support_attachment WHERE tenant_id=2 AND message_id=990101",Integer.class)==1,"Audit failure restores attachment");
   check(service.cleanAutomatically(2L)==1,"Automatic service deletes eligible complete conversation");
   check(j.queryForObject("SELECT COUNT(*) FROM support_conversation WHERE tenant_id=2 AND id=990102",Integer.class)==1,"Legal hold preserved");
   check(j.queryForObject("SELECT COUNT(*) FROM support_conversation WHERE tenant_id=3 AND id=990201",Integer.class)==1,"Other tenant preserved");
   check(service.cleanAutomatically(2L)==0,"Retry is idempotent");
   check(j.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=2 AND action='CHAT_RETENTION_AUTOMATIC_DELETE' AND actor_id IS NULL",Integer.class)==1,"Scheduled audit truthfully uses SYSTEM, not approving actor");
   check(Files.list(Paths.get(directory,"tenant-2")).anyMatch(p->p.getFileName().toString().startsWith("990101-")),"Archive durable before deletion");
   System.out.println("RETENTION_MYSQL_PASS checks="+checks);
  }finally{SecurityContextHolder.clearContext();}
 }
}
