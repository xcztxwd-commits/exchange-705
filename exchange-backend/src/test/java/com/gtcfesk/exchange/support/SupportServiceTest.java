package com.gtcfesk.exchange.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockMultipartFile;
import javax.persistence.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(SupportServiceTest.Config.class)
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class SupportServiceTest {
    static { ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN); }
    @Configuration @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaRepositories(repositoryFactoryBeanClass=com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean.class,basePackages={"com.gtcfesk.exchange.repository", "com.gtcfesk.exchange.admin", "com.gtcfesk.exchange.control"})
    @Import({SupportService.class, SupportSettings.class, SystemConfigService.class, AdminPermissionService.class, SupportPermissionCatalog.class})
    static class Config {
        @Bean DataSource dataSource() {
            String port = System.getProperty("support.test.mysqlPort");
            if (port == null) return new DriverManagerDataSource("jdbc:h2:mem:support_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
            String database = System.getProperty("support.test.mysqlDatabase", "");
            String password = System.getenv("SUPPORT_TEST_MYSQL_PASSWORD");
            // This fixture drops its schema. Explicit opt-in, localhost and a disposable-name guard only.
            if (!"true".equals(System.getenv("SUPPORT_TEST_MYSQL_DISPOSABLE")) || !port.matches("[0-9]{1,5}")
                    || !database.matches("support_qa_[a-z0-9]+") || password == null) throw new IllegalArgumentException("Use the disposable MySQL test runner");
            return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+port+"/"+database
                +"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8", "root", password);
        }
        @Bean com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy(){com.gtcfesk.exchange.control.TenantPolicyService p=org.mockito.Mockito.mock(com.gtcfesk.exchange.control.TenantPolicyService.class);org.mockito.Mockito.when(p.featureEnabled(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);org.mockito.Mockito.when(p.effectiveConfig(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.nullable(String.class))).thenAnswer(c->c.getArgument(1));return p;}
        @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return org.mockito.Mockito.mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
        @Bean com.gtcfesk.exchange.tenant.TenantSecrets secrets(){return org.mockito.Mockito.mock(com.gtcfesk.exchange.tenant.TenantSecrets.class);}
        @Bean com.gtcfesk.exchange.control.TenantReadinessService readiness(){return org.mockito.Mockito.mock(com.gtcfesk.exchange.control.TenantReadinessService.class);}
        @Bean com.gtcfesk.exchange.control.ControlAuditService controlAudit(){return org.mockito.Mockito.mock(com.gtcfesk.exchange.control.ControlAuditService.class);}
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            LocalContainerEntityManagerFactoryBean f = new LocalContainerEntityManagerFactoryBean(); f.setDataSource(ds);
            f.setPackagesToScan("com.gtcfesk.exchange.entity", "com.gtcfesk.exchange.admin", "com.gtcfesk.exchange.support", "com.gtcfesk.exchange.control");
            f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties p = new Properties(); p.setProperty("hibernate.hbm2ddl.auto", "create-drop");
            p.setProperty("hibernate.physical_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            p.put("hibernate.session_factory.statement_inspector", new LockAttemptInspector());
            f.setJpaProperties(p); return f;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f) { return new JpaTransactionManager(f); }
    }
    @Autowired SupportService service;
    @Autowired SupportSettings settings;
    @Autowired SupportPermissionCatalog catalog;
    @Autowired com.gtcfesk.exchange.control.TenantPolicyService policy;
    @Autowired AdminPermissionService permissions;
    @Autowired UserAccountRepository users;
    @Autowired AdminUserRepository admins;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminMenuRepository menus;
    @Autowired AdminRoleMenuRepository grants;
    @Autowired PlatformTransactionManager manager;
    @PersistenceContext EntityManager em;
    Long user, otherUser, admin, otherAdmin, superAdmin;
    String role;
    @BeforeEach void setup() {
        catalog.run();
        SupportSettings.Settings s = new SupportSettings.Settings(); s.mode="internal"; s.inboxEnabled=true; settings.save(s);
        role="care_"+UUID.randomUUID().toString().substring(0,12);
        AdminRole r=new AdminRole(); r.setRoleCode(role); r.setRoleName(role); roles.saveAndFlush(r);
        for (AdminMenu m: menus.findAll()) if (!"directory".equals(m.getMenuType())) {
            AdminRoleMenu g=new AdminRoleMenu(); g.setRoleId(r.getId()); g.setMenuId(m.getId()); grants.saveAndFlush(g);
        }
        user=user(); otherUser=user(); admin=admin(role); otherAdmin=admin(role); superAdmin=admin("super_admin");
        asAdmin(admin); service.presence(true); asAdmin(otherAdmin); service.presence(true); asUser(user);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    Long user() { UserAccount u=new UserAccount(); u.setEmail(UUID.randomUUID()+"@test.invalid"); u.setPasswordHash("unused"); return users.saveAndFlush(u).getId(); }
    Long admin(String role) { AdminUser a=new AdminUser(); a.setAccount(UUID.randomUUID().toString()); a.setEmail(a.getAccount()+"@test.invalid"); a.setPasswordHash("unused"); a.setRole(role); return admins.saveAndFlush(a).getId(); }
    static void auth(Long id, String role) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(), null, Collections.singleton(new SimpleGrantedAuthority("ROLE_"+role)))); }
    void asUser(Long id) { auth(id,"USER"); }
    void asAdmin(Long id) { auth(id,id.equals(superAdmin)?"SUPER_ADMIN":"ADMIN"); }
    String key() { return UUID.randomUUID().toString(); }
    long start() { asUser(user); return service.start("192.0.2.5").getId(); }
    void take(long id) { asAdmin(admin); service.claim(id); }
    @SuppressWarnings("unchecked") List<SupportMessage> messages(long id, boolean admin) { return (List<SupportMessage>)service.detail(id, admin, 0).get("messages"); }

    @Test void queueClaimReplyReadCloseReopenAndHistory() {
        long id=start(); assertEquals(id,service.start("192.0.2.5").getId());
        assertEquals("WAITING",service.sessions(false,"mine",0).get(0).getStatus());
        SupportMessage first=service.send(id,false,key(),"用户问题",null); take(id);
        assertEquals(1L, service.notifications(true).get("chatUnread")); service.read(id,true,first.getId());
        assertEquals(0L, service.notifications(true).get("chatUnread"));
        assertEquals(first.getId(), service.notifications(true).get("latest"));
        SupportMessage reply=service.send(id,true,key(),"客服回复",null);
        asUser(user); assertEquals(1L,service.notifications(false).get("chatUnread")); service.read(id,false,reply.getId());
        assertEquals(0L,service.notifications(false).get("chatUnread")); assertEquals(reply.getId(),service.notifications(false).get("latest")); service.read(id,false,first.getId());
        assertEquals(0L,service.notifications(false).get("chatUnread"));
        service.close(id,false); assertEquals("user-"+user, messages(id,false).get(messages(id,false).size()-1).getSenderName()); assertThrows(ResponseStatusException.class,()->service.send(id,false,key(),"closed",null));
        assertNotEquals(id,service.start("192.0.2.5").getId()); assertEquals(2,service.sessions(false,"mine",0).size());
    }
    @Test void pendingCustomerMessagesSurviveOfflineAndUnassignedReception() {
        // Use real database state; sessions from other tests may still be waiting.
        new TransactionTemplate(manager).execute(s -> { em.createQuery("update SupportPresence p set p.accepting=false where p.tenantId=:tenant").setParameter("tenant",1L).executeUpdate(); return null; });
        asAdmin(admin); assertTrue(service.onlineAgents().isEmpty());
        long before=(Long)service.notifications(true).get("queueUnread");
        long id=start();
        asAdmin(admin); assertEquals(before,service.notifications(true).get("queueUnread"),"welcome-only sessions are not incoming customer messages");
        asUser(user); SupportMessage text=service.send(id,false,key(),"离线留言",null), image=service.send(id,false,key(),"",png());
        assertEquals(0L,service.notifications(false).get("queueUnread"),"users cannot see the staff queue");
        asAdmin(admin); assertEquals(before+2,service.notifications(true).get("queueUnread")); assertEquals(0L,service.notifications(true).get("chatUnread"));
        asAdmin(otherAdmin); assertEquals(before+2,service.notifications(true).get("queueUnread"));
        service.presence(true); assertFalse(service.onlineAgents().isEmpty());
        assertEquals(before+2,service.notifications(true).get("queueUnread"),"online staff without a receptionist must still see the pending messages");
        asAdmin(admin); service.presence(true); service.claim(id); service.presence(false);
        assertEquals(before,service.notifications(true).get("queueUnread")); assertEquals(2L,service.notifications(true).get("chatUnread"),"claim does not acknowledge reading, nor does going offline");
        service.read(id,true,text.getId()); assertEquals(1L,service.notifications(true).get("chatUnread"));
        service.read(id,true,image.getId()); assertEquals(0L,service.notifications(true).get("chatUnread"));
        asAdmin(otherAdmin); assertEquals(0L,service.notifications(true).get("chatUnread"),"other receptionists do not see an assigned agent's unread messages");
        asUser(otherUser); long abandoned=service.start("192.0.2.6").getId(); service.send(abandoned,false,key(),"无人接待",null);
        asAdmin(admin); assertEquals(before+1,service.notifications(true).get("queueUnread"));
        asUser(otherUser); service.close(abandoned,false);
        asAdmin(admin); assertEquals(before,service.notifications(true).get("queueUnread"),"ended queue sessions are no longer pending");
    }
    @Test void pendingQueueRespectsReceptionPermissionAndEffectiveChannel() {
        long id=start(); service.send(id,false,key(),"待处理",null);
        asAdmin(admin); assertTrue((Long)service.notifications(true).get("queueUnread")>0);
        Long roleId=roles.findByTenantIdAndRoleCode(1L,role).get().getId();
        Long claimMenu=menus.findAll().stream().filter(m -> "support:claim".equals(m.getMenuCode())).findFirst().get().getId();
        new TransactionTemplate(manager).execute(s -> { em.createQuery("delete from AdminRoleMenu g where g.tenantId=:tenant and g.roleId=:role and g.menuId=:menu").setParameter("tenant",1L).setParameter("role",roleId).setParameter("menu",claimMenu).executeUpdate(); return null; });
        assertEquals(0L,service.notifications(true).get("queueUnread"),"no queue metadata without reception permission");
        asAdmin(superAdmin); assertTrue((Long)service.notifications(true).get("queueUnread")>0);
        for(String mode:Arrays.asList("off","external")) {
            SupportSettings.Settings s=settings.get(); s.mode=mode; settings.save(s);
            Map<String,Object> notification=service.notifications(true);
            assertEquals(mode,notification.get("mode")); assertEquals(0L,notification.get("queueUnread")); assertEquals(0L,notification.get("chatUnread"));
        }
        SupportSettings.Settings s=settings.get(); s.mode="internal"; settings.save(s);
        org.mockito.Mockito.when(policy.featureEnabled("support")).thenReturn(false);
        try {
            assertEquals("off",service.notifications(true).get("mode"));
            assertEquals(0L,service.notifications(true).get("queueUnread"),"effective feature authorization overrides the local internal setting");
        } finally { org.mockito.Mockito.when(policy.featureEnabled("support")).thenReturn(true); }
        auth(admin,"AGENT"); assertThrows(AccessDeniedException.class,() -> service.notifications(true));
        asUser(user); service.close(id,false);
    }
    @Test void pendingQueueNeverMixesTenantMessages() {
        long id=start(); service.send(id,false,key(),"租户一留言",null);
        asAdmin(superAdmin); long own=(Long)service.notifications(true).get("queueUnread"); assertTrue(own>0);
        assertThrows(AccessDeniedException.class,() -> com.gtcfesk.exchange.tenant.TenantContext.open(2L));
        // Simulate another verified request, not an in-request tenant override.
        com.gtcfesk.exchange.tenant.TenantContext.clear();
        try(com.gtcfesk.exchange.tenant.TenantContext.Scope ignored=com.gtcfesk.exchange.tenant.TenantContext.open(2L)) {
            SupportSettings.Settings s=new SupportSettings.Settings(); s.mode="internal"; settings.save(s);
            assertEquals(0L,service.notifications(true).get("queueUnread"));
            Long foreignUser=user(); asUser(foreignUser); long foreign=service.start("192.0.2.7").getId(); service.send(foreign,false,key(),"租户二留言",null);
            asAdmin(superAdmin); assertEquals(1L,service.notifications(true).get("queueUnread"));
        }
        finally { com.gtcfesk.exchange.tenant.TenantContext.clear(); com.gtcfesk.exchange.tenant.TenantContext.open(1L); }
        asAdmin(superAdmin); assertEquals(own,service.notifications(true).get("queueUnread"));
        asUser(user); service.close(id,false);
    }

    @Test void userAndAgentIsolationIncludingImages() {
        long id=start(); MockMultipartFile image=png(); SupportMessage m=service.send(id,false,key(),"",image);
        asUser(otherUser); assertThrows(AccessDeniedException.class,()->service.detail(id,false,0));
        assertThrows(AccessDeniedException.class,()->service.attachment(m.getId(),false));
        assertThrows(AccessDeniedException.class,()->service.close(id,false)); take(id);
        asAdmin(otherAdmin); assertThrows(AccessDeniedException.class,()->service.detail(id,true,0));
        assertThrows(AccessDeniedException.class,()->service.attachment(m.getId(),true));
        assertThrows(AccessDeniedException.class,()->service.sessions(true,"all",0));
        assertThrows(AccessDeniedException.class,()->service.export(id));
        auth(admin,"AGENT"); assertThrows(AccessDeniedException.class,()->service.sessions(true,"mine",0));
    }
    @Test void supervisorReadDoesNotConsumeUnreadOrReplyAsAnotherAgent() {
        long id=start(); SupportMessage m=service.send(id,false,key(),"help",null); take(id);
        asAdmin(superAdmin); assertFalse(service.sessions(true,"all",0).isEmpty()); service.read(id,true,m.getId());
        assertThrows(AccessDeniedException.class,()->service.send(id,true,key(),"not my session",null));
        asAdmin(admin); assertEquals(1L,service.notifications(true).get("chatUnread"));
    }
    @Test void exportChainIncludesPrivateImageAndDetectsTampering() {
        long id=start(); SupportMessage m=service.send(id,false,key(),"图片",png()); take(id); service.send(id,true,key(),"已收到",null);
        asAdmin(superAdmin); Map<String,Object> exported=service.export(id); assertEquals(true,exported.get("chainValid"));
        assertEquals(1,((Map<?,?>)exported.get("imagesPngBase64")).size());
        new TransactionTemplate(manager).execute(s -> { em.createNativeQuery("update support_message set text='tampered' where id=:id").setParameter("id",m.getId()).executeUpdate(); return null; });
        assertEquals(false,service.export(id).get("chainValid"));
    }
    @Test void messageIdempotencyAndInvalidPayloads() {
        long id=start(); String k=key(); SupportMessage m=service.send(id,false,k,"hello",null);
        assertEquals(m.getId(),service.send(id,false,k,"hello",null).getId());
        assertThrows(ResponseStatusException.class,()->service.send(id,false,k,"different",null));
        assertThrows(IllegalArgumentException.class,()->service.send(id,false,"short","a",null));
        assertThrows(IllegalArgumentException.class,()->service.send(id,false,key(),"  ",null));
        assertThrows(IllegalArgumentException.class,()->service.send(id,false,key(),String.join("",Collections.nCopies(4001,"a")),null));
        assertThrows(ResponseStatusException.class,()->service.send(id,false,key(),"",new MockMultipartFile("file","fake.png","image/png","<svg onload=alert(1)>".getBytes())));
        assertThrows(ResponseStatusException.class,()->SupportService.imageBytes(new MockMultipartFile("file",new byte[5*1024*1024+1])));
    }
    @Test void concurrentStartAndClaimAreSerialized() throws Exception {
        List<Long> created=race(() -> { asUser(user); return service.start("192.0.2.5").getId(); }, () -> { asUser(user); return service.start("192.0.2.5").getId(); });
        assertEquals(created.get(0),created.get(1)); long id=created.get(0);
        List<Boolean> claimed=race(() -> tryClaim(id,admin),()->tryClaim(id,otherAdmin));
        assertNotEquals(claimed.get(0),claimed.get(1));
    }
    boolean tryClaim(long id, Long who) { asAdmin(who); try { service.claim(id); return true; } catch (ResponseStatusException e) { assertEquals(409,e.getRawStatusCode()); return false; } }
    static <T> List<T> race(Callable<T> a,Callable<T> b) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch gate=new CountDownLatch(1);
        try { Future<T> x=pool.submit(()->{gate.await();try(com.gtcfesk.exchange.tenant.TenantContext.Scope ignored=com.gtcfesk.exchange.tenant.TenantContext.open(1L)){return a.call();}finally{SecurityContextHolder.clearContext();}}); Future<T> y=pool.submit(()->{gate.await();try(com.gtcfesk.exchange.tenant.TenantContext.Scope ignored=com.gtcfesk.exchange.tenant.TenantContext.open(1L)){return b.call();}finally{SecurityContextHolder.clearContext();}}); gate.countDown(); return Arrays.asList(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS)); }
        finally { pool.shutdownNow(); }
    }
    @Test void capacityOfflineTransferAndRevocation() {
        SupportSettings.Settings s=settings.get(); s.capacity=1; settings.save(s);
        long id=start(); take(id); asUser(otherUser); long second=service.start("192.0.2.6").getId();
        asAdmin(admin); assertThrows(ResponseStatusException.class,()->service.claim(second));
        service.transfer(id,otherAdmin); assertThrows(AccessDeniedException.class,()->service.detail(id,true,0)); service.claim(second);
        asAdmin(otherAdmin); assertEquals(admin, messages(id,true).get(messages(id,true).size()-1).getSenderId());
        service.presence(false); asAdmin(admin); assertThrows(ResponseStatusException.class,()->service.transfer(second,otherAdmin));
        AdminRole r=roles.findByTenantIdAndRoleCode(1L, role).get(); r.setStatus("disabled");roles.saveAndFlush(r);
        assertThrows(AccessDeniedException.class,()->service.send(second,true,key(),"revoked",null));
    }
    @Test void stalePresenceCannotReceive() {
        long id=start(); new TransactionTemplate(manager).execute(s->{SupportPresence p=em.find(SupportPresence.class,admin);p.setHeartbeatAt(Instant.now().minusSeconds(120));return null;});
        asAdmin(admin); assertThrows(ResponseStatusException.class,()->service.claim(id));
    }
    @Test void disabledFeaturesPreserveHistoryButRejectWrites() {
        long id=start(); SupportSettings.Settings s=settings.get(); s.mode="off";s.inboxEnabled=false;settings.save(s);
        assertThrows(ResponseStatusException.class,()->service.start("192.0.2.5"));
        assertThrows(ResponseStatusException.class,()->service.send(id,false,key(),"blocked",null));
        assertFalse(service.sessions(false,"mine",0).isEmpty()); assertFalse(messages(id,false).isEmpty());
        assertThrows(ResponseStatusException.class,()->service.inbox(false,0));
        asAdmin(admin);assertThrows(ResponseStatusException.class,()->service.sendLetters(key(),Collections.singletonList(user),"title","body"));
    }
    @Test void adminEmailSearchEnrichesOnlyScopedSessionsAndInbox() {
        UserAccount owner=users.findByTenantIdAndId(1L,user).get();owner.setEmail("Alpha_%!"+user+"@test.invalid");owner.setRemark("客户内部备注");users.saveAndFlush(owner);
        long conversation=start();take(conversation);
        asAdmin(admin);service.sendLetters(key(),Arrays.asList(user,otherUser),"title","body");
        AdminUserIdentity identity=new AdminUserIdentity(users,new ObjectMapper().findAndRegisterModules());
        List<Map<String,Object>> sessions=identity.rows(service.sessions(true,"mine",0," ALPHA_%! "));
        assertEquals(1,sessions.size());assertEquals("客户内部备注",sessions.get(0).get("userRemark"));assertEquals(owner.getEmail(),sessions.get(0).get("userEmail"));
        List<Map<String,Object>> inbox=identity.rows(service.inbox(true,0," ALPHA_%! "));
        assertEquals(1,inbox.size());assertEquals(owner.getEmail(),inbox.get(0).get("userEmail"));assertEquals("客户内部备注",inbox.get(0).get("userRemark"));
        assertTrue(service.inbox(true,0,"no-match").isEmpty());assertTrue(service.sessions(true,"mine",0,"no-match").isEmpty());
        asAdmin(otherAdmin);assertTrue(service.inbox(true,0,owner.getEmail()).isEmpty());assertTrue(service.sessions(true,"mine",0,owner.getEmail()).isEmpty());
        asUser(user);Map<?,?> publicSession=new ObjectMapper().findAndRegisterModules().convertValue(service.sessions(false,"mine",0).get(0),Map.class);
        assertFalse(publicSession.containsKey("userEmail"));assertFalse(publicSession.containsKey("userRemark"));
    }
    @Test void inboxAtomicBatchIdempotencyAndReadIsolation() {
        asAdmin(admin);String k=key();assertEquals(2,service.sendLetters(k,Arrays.asList(user,otherUser),"通知","正文"));
        assertEquals(0,service.sendLetters(k,Arrays.asList(user,otherUser),"通知","正文"));
        assertThrows(ResponseStatusException.class,()->service.sendLetters(k,Arrays.asList(user,otherUser),"其他","正文"));
        assertThrows(ResponseStatusException.class,()->service.sendLetters(key(),Arrays.asList(user,999999999L),"不存在","正文"));
        asUser(user);List<InboxLetter> list=service.inbox(false,0);assertEquals(1,list.size());
        assertEquals(1L,service.notifications(false).get("inboxUnread"));
        asUser(otherUser);assertThrows(AccessDeniedException.class,()->service.readLetter(list.get(0).getId()));
        asUser(user);service.readLetter(list.get(0).getId());service.readLetter(list.get(0).getId());assertEquals(0L,service.notifications(false).get("inboxUnread"));
        assertEquals(list.get(0).getId(),service.notifications(false).get("inboxLatest"));
        asUser(otherUser);service.readAllLetters();assertEquals(0L,service.notifications(false).get("inboxUnread"));
        asAdmin(otherAdmin);assertTrue(service.inbox(true,0).isEmpty());
    }
    @Test void ipRulesUseOrderedCidrAndSettingsRejectUnsafeInputs() {
        SupportSettings.Settings s=settings.get();SupportSettings.Rule r=new SupportSettings.Rule();r.cidr="192.0.2.0/24";r.reply="专属欢迎";s.rules.add(r);settings.save(s);
        long id=start();assertEquals("专属欢迎",messages(id,false).get(0).getText());
        assertEquals(s.welcome,settings.welcome(s,"203.0.113.3"));
        assertThrows(RuntimeException.class,()->SupportSettings.parse("{\"mode\":\"broken\"}"));
        assertThrows(RuntimeException.class,()->SupportSettings.parse("{\"adminSound\":\"https://evil.invalid/audio\"}"));
        assertThrows(RuntimeException.class,()->SupportSettings.parse("{\"rules\":[{\"cidr\":\"example.org\",\"reply\":\"x\"}]}"));
        assertThrows(RuntimeException.class,()->SupportSettings.parse("{\"capacity\":0}"));
    }
    @Test void cursorPagesAndRateLimit() {
        long id=start();SupportMessage first=service.send(id,false,key(),"1",null);
        for(int i=0;i<29;i++)service.send(id,false,key(),"message "+i,null);
        assertThrows(ResponseStatusException.class,()->service.send(id,false,key(),"31",null));
        List<?> newer=(List<?>)service.detail(id,false,first.getId()).get("messages");assertEquals(29,newer.size());
        assertThrows(IllegalArgumentException.class,()->service.detail(id,false,-1));
        assertThrows(IllegalArgumentException.class,()->service.sessions(false,"mine",-1));
    }
    static MockMultipartFile png() {
        try { java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return new MockMultipartFile("file","photo.png","image/png",out.toByteArray()); }
        catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    /** Observe SQL preparation without replacing the database, locks, queries or transactions. */
    public static class LockAttemptInspector implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<CountDownLatch> attempts = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            CountDownLatch latch = attempts.get();
            if (latch != null && sql.toLowerCase(Locale.ROOT).contains("for update")) {
                attempts.remove();
                latch.countDown();
            }
            return sql;
        }
    }

    // A third real DB transaction holds the mutex until BOTH service calls reach their lock SQL.
    // Old code has already created RR snapshots here; fixed code has not issued any plain read.
    <T> List<T> behindMutex(Class<?> owner, Long id, Callable<T> a, Callable<T> b) throws Exception {
        return behindMutex(owner, id, a, b, () -> {});
    }
    <T> List<T> behindMutex(Class<?> owner, Long id, Callable<T> a, Callable<T> b, Runnable beforeCommit) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(1), attempted = new CountDownLatch(2), release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(com.gtcfesk.exchange.tenant.TenantOneFixture.worker(() -> new TransactionTemplate(manager).execute(status -> {
                assertNotNull(em.find(owner, id, LockModeType.PESSIMISTIC_WRITE));
                held.countDown();
                try { assertTrue(release.await(20, TimeUnit.SECONDS), "release owner mutex"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                beforeCommit.run();
                return null;
            })));
            assertTrue(held.await(20, TimeUnit.SECONDS), "owner mutex acquired");
            Callable<T> first = observed(a, attempted), second = observed(b, attempted);
            Future<T> x = pool.submit(first), y = pool.submit(second);
            assertTrue(attempted.await(20, TimeUnit.SECONDS), "both transactions reached the owner lock");
            release.countDown(); holder.get(20, TimeUnit.SECONDS);
            return Arrays.asList(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }
    }
    <T> Callable<T> observed(Callable<T> work, CountDownLatch attempted) {
        return () -> {
            LockAttemptInspector.attempts.set(attempted);
            try(com.gtcfesk.exchange.tenant.TenantContext.Scope ignored=com.gtcfesk.exchange.tenant.TenantContext.open(1L)) { return work.call(); }
            finally { LockAttemptInspector.attempts.remove(); SecurityContextHolder.clearContext(); }
        };
    }
    void capacity(int value) { SupportSettings.Settings s = settings.get(); s.capacity = value; settings.save(s); }
    long secondConversation() { asUser(otherUser); return service.start("192.0.2.6").getId(); }
    boolean tryTransfer(long conversation, Long actor, Long target) {
        asAdmin(actor);
        try { service.transfer(conversation, target); return true; }
        catch (ResponseStatusException e) { assertEquals(409, e.getRawStatusCode()); return false; }
    }
    long activeFor(Long owner) {
        return new TransactionTemplate(manager).execute(s -> em.createQuery(
            "select count(c) from SupportConversation c where adminId=:a and status='ACTIVE'", Long.class)
            .setParameter("a", owner).getSingleResult());
    }
    void validChains(long... ids) { asAdmin(superAdmin); for (long id : ids) assertEquals(true, service.export(id).get("chainValid")); }

    @RepeatedTest(3) void lockedStartIsIdempotent() throws Exception {
        List<Long> ids = behindMutex(UserAccount.class, user,
            () -> { asUser(user); return service.start("192.0.2.5").getId(); },
            () -> { asUser(user); return service.start("192.0.2.5").getId(); });
        assertEquals(ids.get(0), ids.get(1), "same user must get the same committed conversation");
        asUser(user); assertEquals(1, service.sessions(false, "mine", 0).size());
        assertEquals(1, messages(ids.get(0), false).size()); validChains(ids.get(0));
    }
    @RepeatedTest(3) void lockedClaimRespectsCapacity() throws Exception {
        capacity(1); long first = start(), second = secondConversation();
        List<Boolean> results = behindMutex(AdminUser.class, admin,
            () -> tryClaim(first, admin), () -> tryClaim(second, admin));
        assertNotEquals(results.get(0), results.get(1), "capacity=1 must admit exactly one conversation");
        assertEquals(1, activeFor(admin)); validChains(first, second);
    }
    @RepeatedTest(3) void lockedTransfersShareTargetCapacity() throws Exception {
        long first = start(); take(first); long second = secondConversation(); take(second); capacity(1);
        List<Boolean> results = behindMutex(AdminUser.class, otherAdmin,
            () -> tryTransfer(first, admin, otherAdmin), () -> tryTransfer(second, admin, otherAdmin));
        assertNotEquals(results.get(0), results.get(1), "two transfers must not overbook their target");
        assertEquals(1, activeFor(otherAdmin)); assertEquals(1, activeFor(admin)); validChains(first, second);
    }
    @RepeatedTest(3) void lockedClaimAndTransferShareCapacity() throws Exception {
        capacity(1); long first = start(); take(first); long second = secondConversation();
        List<Boolean> results = behindMutex(AdminUser.class, otherAdmin,
            () -> tryTransfer(first, admin, otherAdmin), () -> tryClaim(second, otherAdmin));
        assertNotEquals(results.get(0), results.get(1), "claim and transfer share one admission limit");
        assertEquals(1, activeFor(otherAdmin)); validChains(first, second);
    }
    @RepeatedTest(3) void oppositeTransfersDoNotLockEachOthersSessions() throws Exception {
        capacity(2); long first = start(); take(first); long second = secondConversation(); asAdmin(otherAdmin); service.claim(second);
        List<Boolean> result = race(() -> tryTransfer(first, admin, otherAdmin), () -> tryTransfer(second, otherAdmin, admin));
        assertEquals(Arrays.asList(true, true), result); assertEquals(1, activeFor(admin)); assertEquals(1, activeFor(otherAdmin));
        asAdmin(admin); assertThrows(AccessDeniedException.class, () -> service.detail(first, true, 0));
        asAdmin(otherAdmin); assertThrows(AccessDeniedException.class, () -> service.detail(second, true, 0)); validChains(first, second);
    }
    @RepeatedTest(3) void lockedImageRetryReturnsOneMessage() throws Exception {
        long id = start(); take(id); String request = key();
        List<Long> sent = behindMutex(SupportConversation.class, id,
            () -> { asAdmin(admin); return service.send(id, true, request, "", png()).getId(); },
            () -> { asAdmin(admin); return service.send(id, true, request, "", png()).getId(); });
        assertEquals(sent.get(0), sent.get(1)); asUser(user);
        assertEquals(1, messages(id, false).stream().filter(SupportMessage::isImage).count()); validChains(id);
    }
    @RepeatedTest(3) void lockedInboxRetrySendsOneBatch() throws Exception {
        String request = key();
        Callable<Integer> send = () -> { asAdmin(admin); return service.sendLetters(request, Arrays.asList(user, otherUser), "notice", "body"); };
        List<Integer> counts = behindMutex(AdminUser.class, admin, send, send);
        assertTrue(counts.contains(0)); assertTrue(counts.contains(2));
        asUser(user); assertEquals(1, service.inbox(false, 0).size()); asUser(otherUser); assertEquals(1, service.inbox(false, 0).size());
    }
    @RepeatedTest(3) void lockedPresenceInitializationIsUnique() throws Exception {
        new TransactionTemplate(manager).execute(s -> { em.remove(em.find(SupportPresence.class, admin)); return null; });
        Callable<Long> online = () -> { asAdmin(admin); return service.presence(true).getAdminId(); };
        assertEquals(Arrays.asList(admin, admin), behindMutex(AdminUser.class, admin, online, online));
    }
    @RepeatedTest(3) void differentUsersCanStartTogether() throws Exception {
        List<Long> ids = race(() -> { asUser(user); return service.start("192.0.2.5").getId(); },
            () -> { asUser(otherUser); return service.start("192.0.2.6").getId(); });
        assertNotEquals(ids.get(0), ids.get(1)); validChains(ids.get(0), ids.get(1));
    }
    @RepeatedTest(3) void permissionRevokedWhileWaitingCannotAdmit() throws Exception {
        long id = start();
        Callable<Boolean> claim = () -> { asAdmin(admin); assertThrows(AccessDeniedException.class, () -> service.claim(id)); return true; };
        List<Boolean> rejected = behindMutex(AdminUser.class, admin, claim, claim, () -> {
            AdminRole row = em.createQuery("from AdminRole where roleCode=:r", AdminRole.class).setParameter("r", role).getSingleResult();
            row.setStatus("disabled");
        });
        assertEquals(Arrays.asList(true, true), rejected); assertEquals(0, activeFor(admin));
    }

}
