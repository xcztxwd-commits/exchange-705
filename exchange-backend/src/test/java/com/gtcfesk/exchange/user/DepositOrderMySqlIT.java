package com.gtcfesk.exchange.user;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Inherited money/checkpoint matrix plus actual migrated MySQL, A/B owners and real CONTROL audit rows. */
public class DepositOrderMySqlIT extends DepositOrderServiceTest {
 @Autowired ControlAuditLogRepository auditLogs;
 @BeforeAll static void requireMySql(){assertNotNull(System.getProperty("deposit.mysql.fixture"),"Missing identified/restored fixture is failure, not skip");}
 @Override <T extends DepositOrderService> T wired(T service){
  super.wired(service);ReflectionTestUtils.setField(service,"controlAudit",new ControlAuditService(auditLogs));return service;
 }
 @Test void mysql57Only()throws Exception{try(java.sql.Connection c=dataSource.getConnection()){
  assertTrue(c.getMetaData().getDatabaseProductVersion().startsWith("5.7."));assertTrue(c.getCatalog().startsWith("mt705_probe_"));
  assertEquals(1L,new JdbcTemplate(dataSource).queryForObject("select count(*) from tenant where id=1",Long.class));
 }}
 @Test void migratedHistoricalFactsRemainUnknown(){
  List<DepositRecord> historical=records.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L,7000001L);assertFalse(historical.isEmpty());
  for(DepositRecord d:historical){assertEquals("LEGACY_UNKNOWN",d.getSource());assertTrue(d.getOrderNo().startsWith("LEGACY-DEP-"));assertNull(d.getReviewedAt());assertNull(d.getCreatedById());assertNull(d.getCreditedAt());assertNull(d.getFeeRate());assertFalse(credits.findByTenantIdAndDepositRecordId(1L,d.getId()).isPresent());}
  equal("25",assets.findByTenantIdAndUserIdAndCoin(1L,7000001L,"FUND").get().getAvailable());
 }
 long control(){
  JdbcTemplate db=new JdbcTemplate(dataSource);String account="deposit-"+UUID.randomUUID();
  db.update("insert into control_admin(account,password_hash,enabled,mfa_enabled,session_version,row_version) values(?,'not-a-login',1,0,0,0)",account);
  long actor=db.queryForObject("select id from control_admin where account=?",Long.class,account);
  UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("-"+actor,null,Arrays.asList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),new SimpleGrantedAuthority("ROLE_CONTROL_ACCESS")));
  auth.setDetails(new ControlIdentity(actor,1L,"deposit-it-"+actor));SecurityContextHolder.getContext().setAuthentication(auth);return actor;
 }
 long audits(long actor){return new JdbcTemplate(dataSource).queryForObject("select count(*) from control_audit_log where actor_id=? and tenant_id=1 and access_session_id=? and action='DEPOSIT_CREATE'",Long.class,actor,"deposit-it-"+actor);}
 @Test void controlReplayPersistsRealOperatorAndSingleAudit(){
  long actor=control();DepositOrderRequest request=request();DepositRecord d=service.manual(request);service.manual(request);
  assertEquals("CONTROL",d.getCreatedByType());assertEquals(actor,d.getCreatedById());assertEquals("总控管理",d.getCreatedByName());
  DepositCreditRecord credit=credits.findByTenantIdAndDepositRecordId(1L,d.getId()).get();assertEquals("CONTROL",credit.getOperatorType());assertEquals(actor,credit.getOperatorId());
  equal("125",balance("FUND"));assertEquals(1,count());assertEquals(1,audits(actor));assertEquals(0L,new JdbcTemplate(dataSource).queryForObject("select count(*) from admin_user where account=?",Long.class,"-"+actor));
 }
 @Test void auditDatabaseFailureRollsBackMoneyReceiptOrderAndCanRetry(){
  long actor=control();JdbcTemplate db=new JdbcTemplate(dataSource);DepositOrderRequest request=request();String trigger="deposit_it_fail_"+actor;
  db.execute("create trigger "+trigger+" before insert on control_audit_log for each row begin if NEW.actor_id="+actor+" then signal sqlstate '45000' set message_text='fixture audit rejected'; end if; end");
  try{assertThrows(RuntimeException.class,()->service.manual(request));equal("25",balance("FUND"));assertEquals(0,count());assertEquals(0,audits(actor));assertEquals(0L,db.queryForObject("select count(*) from deposit_credit_record where tenant_id=1 and user_id=?",Long.class,user.getId()));}
  finally{db.execute("drop trigger "+trigger);}
  service.manual(request);equal("125",balance("FUND"));assertEquals(1,audits(actor));
 }
 @Test void missingAndOtherTenantCannotReadOrWriteKnownIds(){
  DepositRecord original=service.manual(request());
  TenantContext.clear();assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.manual(request()));
  try(TenantContext.Scope ignored=TenantContext.open(2L)){
   assertFalse(records.findByTenantIdAndId(2L,original.getId()).isPresent());assertFalse(users.findByTenantIdAndId(2L,user.getId()).isPresent());
   assertThrows(RuntimeException.class,()->service.manual(request()));
  }finally{TenantContext.open(1L);}
  equal("125",balance("FUND"));assertEquals(1,count());assertEquals(1L,new JdbcTemplate(dataSource).queryForObject("select count(*) from deposit_credit_record where tenant_id=1 and user_id=?",Long.class,user.getId()));
 }
}
