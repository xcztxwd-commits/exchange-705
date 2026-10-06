package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.simulation.AccountInspection;
import com.gtcfesk.exchange.support.SupportService;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual fixed SQL and transaction proxies on disposable H2; duplicate IDs/emails prove joins cannot cross tenants. */
@SpringJUnitConfig(ControlAllTenantsReadTest.Config.class)
class ControlAllTenantsReadTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean javax.persistence.EntityManagerFactory entityManagerFactory(){return mock(javax.persistence.EntityManagerFactory.class);}
  @Bean DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:control_all_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
  @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
  @Bean PlatformTransactionManager manager(DataSource source){return new DataSourceTransactionManager(source);}
  @Bean AccountInspection inspection(JdbcTemplate jdbc){return new AccountInspection(jdbc);}
  @Bean ControlReadQueryService queries(JdbcTemplate jdbc){return new ControlReadQueryService(jdbc,mock(SupportService.class));}
 }
 @Autowired JdbcTemplate jdbc;@Autowired AccountInspection inspection;@Autowired ControlReadQueryService queries;
 final List<String> kinds=Arrays.asList("users","wallets","contracts","options","deposits","withdrawals","transfers","loans","financial","equity");
 final Map<String,Set<String>> schema=new LinkedHashMap<>();
 @BeforeEach void fixture(){
  TenantContext.clear();login("ROLE_CONTROL",99L,null,null,true);
  for(String kind:kinds){String[] type=AccountInspection.type(kind);Set<String> columns=new LinkedHashSet<>(Arrays.asList(type[2].split(",")));columns.add("tenant_id");schema.put(type[0],columns);}
  schema.get("user_account").addAll(Arrays.asList("user_type","parent_user_id","last_login_at","password_hash","current_token"));
  schema.put("kyc_record",new LinkedHashSet<>(Arrays.asList("tenant_id,id,user_id,real_name,id_number,status,review_remark,reviewed_by,reviewed_at,created_at,updated_at,front_image".split(","))));
  schema.put("admin_user",new LinkedHashSet<>(Arrays.asList("tenant_id,id,account,email,role,enabled,must_change_password,created_at,updated_at,password_hash,current_token".split(","))));
  schema.put("support_conversation",new LinkedHashSet<>(Arrays.asList("tenant_id,id,status".split(","))));
  jdbc.execute("CREATE TABLE IF NOT EXISTS tenant(id BIGINT PRIMARY KEY,name VARCHAR(64))");jdbc.execute("DELETE FROM tenant");
  for(Map.Entry<String,Set<String>> table:schema.entrySet()){
   List<String> definitions=new ArrayList<>();for(String column:table.getValue())definitions.add(column+" "+sqlType(column));
   jdbc.execute("CREATE TABLE IF NOT EXISTS "+table.getKey()+"("+String.join(",",definitions)+",PRIMARY KEY(tenant_id,id))");jdbc.execute("DELETE FROM "+table.getKey());
  }
  jdbc.update("INSERT INTO tenant VALUES(1,'Tenant A'),(2,'Tenant B'),(-1,'Invalid owner')");
  for(int index=0;index<35;index++){
   long tenant=index%2+1,id=index/2+1;
   for(String kind:kinds){String[] type=AccountInspection.type(kind);if("users".equals(kind))jdbc.update("INSERT INTO user_account(id,tenant_id,email,remark,status,user_type,parent_user_id,password_hash,current_token) VALUES(?,?,?,?,'PENDING','agent',?,'never-return-password','never-return-token')",id,tenant,"shared%_!@example.test",tenant==1?"A remark":"B remark",id==1?null:1L);else jdbc.update("INSERT INTO "+type[0]+"(id,tenant_id,user_id) VALUES(?,?,1)",id,tenant);}
   jdbc.update("INSERT INTO kyc_record(id,tenant_id,user_id,real_name,id_number,status,front_image) VALUES(?,?,1,'Person','SECRET-DOCUMENT-5678','PENDING','never-return-image')",id,tenant);
   jdbc.update("INSERT INTO admin_user(id,tenant_id,account,email,role,enabled,must_change_password,password_hash,current_token) VALUES(?,?,?,?,'admin',TRUE,FALSE,'never-return-password','never-return-token')",id,tenant,"admin-"+id,"shared%_!@example.test");
  }
  for(String kind:kinds){String[] type=AccountInspection.type(kind);if(schema.get(type[0]).contains("status"))jdbc.update("UPDATE "+type[0]+" SET status='PENDING'");}
  jdbc.update("UPDATE asset_account SET coin=CASE WHEN MOD(id,2)=0 THEN 'FUND' ELSE 'CONTRACT' END,available=tenant_id*10,frozen=1");
  for(String table:Arrays.asList("deposit_record","withdraw_record"))jdbc.update("UPDATE "+table+" SET currency=CASE WHEN MOD(id,2)=0 THEN 'EUR' ELSE 'USD' END,amount=tenant_id*100");
  jdbc.update("INSERT INTO support_conversation(id,tenant_id,status) VALUES(1,1,'OPEN'),(2,1,'CLOSED'),(1,2,'OPEN'),(1,9,'OPEN')");
  jdbc.update("INSERT INTO user_account(id,tenant_id,email,remark,status,user_type,password_hash) VALUES(1,9,'shared%_!@example.test','UNREGISTERED','PENDING','agent','never-return-password'),(1,-1,'shared%_!@example.test','INVALID','PENDING','agent','never-return-password')");
  jdbc.update("INSERT INTO asset_account(id,tenant_id,user_id,coin,available,frozen) VALUES(1,9,1,'FUND',999999,999999)");
 }
 String sqlType(String c){if(c.equals("id")||c.endsWith("_id")||c.equals("reviewed_by"))return "BIGINT";if(Arrays.asList("available","frozen","amount","actual_amount","fee","profit","margin","total","quantity","open_price","close_price","purchase_amount","total_yield","daily_yield","total_interest","overdue_fee","repayment_amount").contains(c))return "DECIMAL(24,8)";if(c.endsWith("_at")||c.endsWith("_time"))return "TIMESTAMP";if(Arrays.asList("enabled","must_change_password","contract_signed").contains(c))return "BOOLEAN";return "VARCHAR(255)";}
 void login(String role,Long actor,Long tenant,String access,boolean authenticated){UsernamePasswordAuthenticationToken a=authenticated?new UsernamePasswordAuthenticationToken("actor",null,Collections.singletonList(new SimpleGrantedAuthority(role))):new UsernamePasswordAuthenticationToken("actor",null);a.setDetails(new ControlIdentity(actor,tenant,access));SecurityContextHolder.getContext().setAuthentication(a);}
 @AfterEach void cleanup(){TenantContext.clear();SecurityContextHolder.clearContext();}
 @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> result){return (List<Map<String,Object>>)result.get("rows");}
 @SuppressWarnings("unchecked") List<Map<String,Object>> table(Map<String,Object> result,String key){return (List<Map<String,Object>>)result.get(key);}
 @Test void allBusinessKindsUseGlobalPagingStableOrderingAndSafeSameTenantUserJoins(){
  List<Map<String,Object>> before=jdbc.queryForList("SELECT * FROM user_account ORDER BY tenant_id,id");
  for(String kind:kinds){Map<String,Object> first=inspection.readAll(kind,null,null,null,1,20),second=inspection.readAll(kind,null,null,null,2,20);assertEquals(35L,first.get("total"),kind);assertEquals(20,rows(first).size());assertEquals(15,rows(second).size());assertTrue(rows(inspection.readAll(kind,null,null,null,3,20)).isEmpty());
   List<Map<String,Object>> actual=new ArrayList<>(rows(first));actual.addAll(rows(second));Set<String> keys=new HashSet<>();long lastId=Long.MAX_VALUE,lastOwner=Long.MAX_VALUE;
   for(Map<String,Object> row:actual){long owner=((Number)row.get("tenant_id")).longValue(),id=((Number)row.get("id")).longValue();assertTrue(owner==1||owner==2);assertEquals(owner==1?"Tenant A":"Tenant B",row.get("tenant_name"));assertTrue(id<lastId||id==lastId&&owner<lastOwner);lastId=id;lastOwner=owner;assertTrue(keys.add(owner+":"+id));assertEquals(owner==1?"A remark":"B remark",row.get("users".equals(kind)?"remark":"user_remark"));assertFalse(row.containsKey("password_hash"));assertFalse(row.containsKey("current_token"));}
   assertEquals("tenant_id",((String[])first.get("columns"))[0]);assertEquals(35L,inspection.readAll(kind,null,"SHARED%_!",null,1,100).get("total"));
   try(TenantContext.Scope ignored=TenantContext.open(1L)){Map<String,Object> scoped=inspection.read(kind,null,null,null,1,100);assertEquals(18L,scoped.get("total"));assertFalse(rows(scoped).get(0).containsKey("tenant_id"));}
  }
  assertEquals(before,jdbc.queryForList("SELECT * FROM user_account ORDER BY tenant_id,id"));assertNull(TenantContext.currentTenantId());
 }
 @Test void allRegulatoryKindsKeepMaskingAndAgentParentAndSubordinateOwnership(){
  for(String kind:Arrays.asList("kyc","admins","agents")){Map<String,Object> first=queries.allRecords(kind,null,null,null,1,20),second=queries.allRecords(kind,null,null,null,2,20);assertEquals(35L,first.get("total"));assertEquals(20,rows(first).size());assertEquals(15,rows(second).size());assertEquals(35L,queries.allRecords(kind,null,"shared%_!",null,1,100).get("total"));assertEquals("kyc".equals(kind)?35L:2L,queries.allRecords(kind,1L,null,null,1,100).get("total"));
   for(Map<String,Object> row:rows(first)){long owner=((Number)row.get("tenant_id")).longValue();assertEquals(owner==1?"Tenant A":"Tenant B",row.get("tenant_name"));if("kyc".equals(kind)){assertEquals("***5678",row.get("id_number"));assertFalse(row.containsKey("front_image"));}if("agents".equals(kind)&&((Number)row.get("id")).longValue()>1){assertEquals(owner==1?"A remark":"B remark",row.get("parent_user_remark"));assertEquals(0L,((Number)row.get("subordinate_count")).longValue());}assertFalse(row.containsKey("password_hash"));assertFalse(row.containsKey("current_token"));}
   if("agents".equals(kind)){for(Map<String,Object> row:rows(queries.allRecords(kind,1L,null,null,1,20))){long owner=((Number)row.get("tenant_id")).longValue();assertEquals(owner==1?17L:16L,((Number)row.get("subordinate_count")).longValue());}}
   try(TenantContext.Scope ignored=TenantContext.open(2L)){assertEquals(17L,queries.records(kind,null,null,null,1,100).get("total"));}
  }
  assertEquals(35L,queries.allRecords("kyc",1L,null,"PENDING",1,100).get("total"));assertEquals(0L,queries.allRecords("kyc",null,null,"APPROVED",1,100).get("total"));
 }
 @Test void allStatisticsCountAccountsNotDistinctEmailsAndRetainTenantAccountTypeAndCurrency(){
  Map<String,Object> report=queries.allStatistics();assertNull(report.get("tenantId"));Map<?,?> counts=(Map<?,?>)report.get("counts");assertEquals(35L,counts.get("users"));assertEquals(35L,counts.get("agents"));assertEquals(35L,counts.get("admins"));assertEquals(35L,counts.get("pendingKyc"));assertEquals(2L,counts.get("openSupport"));assertEquals(4,table(report,"assets").size());assertEquals(4,table(report,"deposits").size());
  for(long owner:new long[]{1,2}){Map<String,Object> scoped;try(TenantContext.Scope ignored=TenantContext.open(owner)){scoped=queries.statistics();}
   for(String key:Arrays.asList("assets","contracts","options","deposits","withdrawals")){List<Map<String,Object>> normalized=new ArrayList<>();for(Map<String,Object> row:table(report,key))if(((Number)row.get("tenant_id")).longValue()==owner){Map<String,Object> copy=new LinkedHashMap<>(row);assertEquals(owner==1?"Tenant A":"Tenant B",copy.remove("tenant_name"));copy.remove("tenant_id");normalized.add(copy);}assertEquals(table(scoped,key),normalized,key+" tenant "+owner);}
  }
  assertTrue(table(report,"assets").stream().allMatch(row->((BigDecimal)row.get("available")).compareTo(new BigDecimal("999999"))<0));assertNull(TenantContext.currentTenantId());
 }
 @Test void explicitAllCannotBeInferredFromMissingContextAndFailsClosedForEveryWrongIdentity(){
  for(String role:Arrays.asList("ROLE_ADMIN","ROLE_USER","ROLE_AGENT","ROLE_CONTROL_ACCESS")){login(role,99L,null,null,true);assertAllDenied();}
  login("ROLE_CONTROL",99L,1L,null,true);assertAllDenied();login("ROLE_CONTROL",99L,null,"access",true);assertAllDenied();login("ROLE_CONTROL",0L,null,null,true);assertAllDenied();login("ROLE_CONTROL",99L,null,null,false);assertAllDenied();SecurityContextHolder.clearContext();assertAllDenied();
  login("ROLE_CONTROL",99L,null,null,true);try(TenantContext.Scope ignored=TenantContext.open(1L)){assertAllDenied();}
  assertThrows(RuntimeException.class,()->inspection.read("users",null,null,1,20));assertThrows(RuntimeException.class,()->queries.records("admins",null,null,1,20));assertThrows(RuntimeException.class,queries::statistics);
  assertThrows(IllegalArgumentException.class,()->inspection.readAll("users;DELETE",null,null,null,1,20));assertThrows(IllegalArgumentException.class,()->inspection.readAll("users",-1L,null,null,1,20));assertThrows(IllegalArgumentException.class,()->inspection.readAll("users",null,null,null,1,101));assertThrows(IllegalArgumentException.class,()->inspection.readAll("users",null,null,null,100001,20));assertThrows(IllegalArgumentException.class,()->queries.allRecords("admins",null,null,"PENDING",1,20));assertThrows(IllegalArgumentException.class,()->queries.allRecords("kyc",null,null,null,0,20));
 }
 void assertAllDenied(){assertThrows(org.springframework.security.access.AccessDeniedException.class,()->inspection.readAll("users",null,null,null,1,20));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->queries.allRecords("admins",null,null,null,1,20));assertThrows(org.springframework.security.access.AccessDeniedException.class,queries::allStatistics);}
}
