package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import javax.persistence.EntityManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Defaults become missing tenant grants only; explicit values, readiness, state and transaction gates remain authoritative. */
class ControlPolicyDefinitionTest {
 private ControlPolicyDefinitionRepository repository;private ControlAuditService audit;private ControlPolicyDefinitionService service;
 private Map<String,ControlPolicyDefinition> stored;private TenantRepository catalogTenants;private org.springframework.beans.factory.ObjectProvider<TenantManagementService> provider;
 @BeforeEach void setup(){
  identity(new ControlIdentity(7L,null,null));repository=mock(ControlPolicyDefinitionRepository.class);audit=mock(ControlAuditService.class);stored=new HashMap<>();service=new ControlPolicyDefinitionService(repository,audit);
  ControlPolicyDefinition anchor=new ControlPolicyDefinition();anchor.setKey("feature.registration");anchor.setName("用户注册");anchor.setOptionsJson("[\"false\",\"true\"]");anchor.setDefaultValue("false");anchor.setVersion(0L);stored.put(anchor.getKey(),anchor);
  catalogTenants=mock(TenantRepository.class);provider=mock(org.springframework.beans.factory.ObjectProvider.class);ReflectionTestUtils.setField(service,"tenants",catalogTenants);ReflectionTestUtils.setField(service,"management",provider);when(catalogTenants.findAll()).thenReturn(Collections.emptyList());
  when(repository.lockDefaults()).thenAnswer(call->Optional.ofNullable(stored.get("feature.registration")));
  when(repository.findAll()).thenAnswer(call->new ArrayList<>(stored.values()));when(repository.findById(anyString())).thenAnswer(call->Optional.ofNullable(stored.get(call.getArgument(0))));
  when(repository.saveAndFlush(any())).thenAnswer(call->{ControlPolicyDefinition row=call.getArgument(0);row.setVersion(row.getVersion()==null?0L:row.getVersion()+1);stored.put(row.getKey(),row);return row;});
 }
 @AfterEach void clear(){SecurityContextHolder.clearContext();TenantContext.clear();}
 private static void identity(ControlIdentity details){UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("operator",null,Collections.emptyList());auth.setDetails(details);SecurityContextHolder.getContext().setAuthentication(auth);}
 private ControlPolicyDefinitionService.Input input(String key,String name,String value,String...options){ControlPolicyDefinitionService.Input in=new ControlPolicyDefinitionService.Input();in.key=key;in.name=name;in.defaultValue=value;in.options=Arrays.asList(options);in.version=stored.containsKey(key)?stored.get(key).getVersion():null;return in;}
 private ControlPolicyDefinitionService.View view(String key){return service.list().stream().filter(row->row.key.equals(key)).findFirst().orElseThrow(AssertionError::new);}
 @Test void catalogHasEveryRuntimeFeatureAndNamesRetentionWithoutGranting(){
  for(String feature:TenantPolicyService.FEATURES){ControlPolicyDefinitionService.View row=view("feature."+feature);assertFalse(row.name.isEmpty());assertEquals("false",row.defaultValue);assertEquals(Arrays.asList("false","true"),row.options);assertTrue(row.tenantEditable);}
  assertEquals(TenantPolicyService.FEATURES.size()+3,service.list().size());assertEquals("365",view("retention.keep_days").defaultValue);assertFalse(view("retention.auto_delete_enabled").tenantEditable);verify(repository,never()).saveAndFlush(any());verifyNoInteractions(audit);
 }
 @Test void independentControlIdentityRequiredForReadWriteAndChoiceValidation(){
  for(ControlIdentity identity:Arrays.asList(null,new ControlIdentity(7L,2L,"session"),new ControlIdentity(7L,2L,null))){
   identity(identity);assertThrows(AccessDeniedException.class,()->service.list());assertThrows(AccessDeniedException.class,()->service.save(input("feature.option","期权","false","false","true")));assertThrows(AccessDeniedException.class,()->service.requireAllowedValue("feature.option","false"));assertThrows(AccessDeniedException.class,service::initialDefaults);
  }
  verifyNoInteractions(repository,audit);
 }
 @Test void defaultAuthorizesMissingRowsAndNeverOverridesExistingValuesOrLocks(){
  TenantFixture fixture=new TenantFixture();fixture.add(3L,"ACTIVE");fixture.add(4L,"ACTIVE");fixture.add(5L,"ACTIVE");fixture.connectDefaults();
  TenantPolicy disabled=fixture.grant(3L,"feature.registration","false",false),locked=fixture.grant(4L,"feature.registration","true",true),empty=fixture.grant(5L,"feature.registration","",true);disabled.setVersion(8L);
  ControlPolicyDefinitionService.View saved=service.save(input("feature.registration","开放用户注册","true","false","true"));assertEquals(1L,saved.version);assertEquals(1,saved.appliedTenants);assertEquals(3,saved.retainedTenants);assertEquals("开放用户注册",view("feature.registration").name);
  assertEquals("true",fixture.get(2L,"feature.registration").getValue());assertTrue(fixture.get(2L,"feature.registration").isLocked());assertSame(disabled,fixture.get(3L,"feature.registration"));assertEquals("false",disabled.getValue());assertFalse(disabled.isLocked());assertEquals(8L,disabled.getVersion());assertSame(locked,fixture.get(4L,"feature.registration"));assertSame(empty,fixture.get(5L,"feature.registration"));assertEquals("",empty.getValue());
  verify(fixture.grants,times(1)).saveAndFlush(any());verify(fixture.readiness).requireFeatureReady(2L,"registration");verify(fixture.readiness).requireReady(2L);
  verify(audit).record(eq(7L),eq(2L),isNull(),eq("POLICY_UPDATE"),eq("feature.registration"),eq("SUCCESS"),anyString(),eq("授权策略默认值自动应用"));
  verify(audit).record(eq(7L),isNull(),isNull(),eq("POLICY_DEFINITION_UPDATE"),eq("feature.registration"),eq("SUCCESS"),contains("applied=1;retained=3"),eq("总控授权策略管理"));
  TenantPolicyService runtime=new TenantPolicyService(fixture.tenants,fixture.grants);ReflectionTestUtils.setField(runtime,"readiness",fixture.readiness);TenantContext.open(2L);assertDoesNotThrow(()->runtime.requireNewBusiness("registration"));
  saved=service.save(input("feature.registration","用户注册","false","false","true"));assertEquals(0,saved.appliedTenants);assertEquals(4,saved.retainedTenants);assertEquals("true",fixture.get(2L,"feature.registration").getValue());assertEquals(1L,fixture.tenant(2L).getPolicyVersion());assertEquals(0L,fixture.tenant(3L).getPolicyVersion());
 }
 @Test void defaultsFailClosedWithoutMigratedAnchor(){
  stored.remove("feature.registration");assertThrows(IllegalStateException.class,service::initialDefaults);assertThrows(IllegalStateException.class,()->service.save(input("feature.option","期权","true","false","true")));verify(repository,never()).saveAndFlush(any());verifyNoInteractions(audit);
 }
 @Test void closedTenantDefaultsDoNotOpenBusinessOrSkipActivationReadiness(){
  for(String status:Arrays.asList("DRAFT","STOP_NEW","MAINTENANCE","DISABLED")){
   TenantFixture fixture=new TenantFixture();fixture.tenant(2L).setStatus(status);fixture.tenant(2L).setConfigReady(false);fixture.tenant(2L).setDomainVerified(false);
   assertTrue(fixture.management.applyDefault(2L,"feature.registration","true"));verifyNoInteractions(fixture.readiness);assertEquals(status,fixture.tenant(2L).getStatus());assertFalse(fixture.tenant(2L).isConfigReady());
   TenantPolicyService runtime=new TenantPolicyService(fixture.tenants,fixture.grants);ReflectionTestUtils.setField(runtime,"readiness",fixture.readiness);TenantContext.open(2L);assertThrows(AccessDeniedException.class,()->runtime.requireNewBusiness("registration"));
   doThrow(new AccessDeniedException("注册基础配置未齐备")).when(fixture.readiness).requireReady(2L);ControlController.TenantInput activate=new ControlController.TenantInput();activate.status="ACTIVE";activate.configReady=true;activate.reason="测试激活核验";assertThrows(AccessDeniedException.class,()->fixture.management.update(2L,activate));assertEquals(status,fixture.tenant(2L).getStatus());
  }
 }
 @Test void automaticSupportChannelRejectsConflictAndPreservesMatchingExistingLock(){
  TenantFixture fixture=new TenantFixture();fixture.tenant(2L).setStatus("DRAFT");TenantPolicy support=fixture.grant(2L,"feature.support","false",false);support.setVersion(9L);
  assertThrows(ResponseStatusException.class,()->fixture.management.applyDefault(2L,"config.support.channel","internal"));assertNull(fixture.get(2L,"config.support.channel"));assertEquals("false",support.getValue());verify(fixture.grants,never()).saveAndFlush(any());verifyNoInteractions(audit);
  support.setValue("true");assertTrue(fixture.management.applyDefault(2L,"config.support.channel","internal"));assertSame(support,fixture.get(2L,"feature.support"));assertFalse(support.isLocked());assertEquals(9L,support.getVersion());assertEquals("false",fixture.get(2L,"feature.external_support").getValue());assertTrue(fixture.get(2L,"feature.external_support").isLocked());verifyNoInteractions(fixture.readiness);
 }
 @Test void automaticConfigDefaultsAreRealLockedRowsAndStillUseOutboundValidation(){
  TenantFixture fixture=new TenantFixture();fixture.tenant(2L).setStatus("DRAFT");assertTrue(fixture.management.applyDefault(2L,"config.example.mode","normal"));
  TenantPolicyService runtime=new TenantPolicyService(fixture.tenants,fixture.grants);TenantContext.open(2L);assertEquals("normal",runtime.effectiveConfig("example.mode","strict"));verify(fixture.outbound).validateConfig("example.mode","normal");
  doThrow(new IllegalArgumentException("未经授权的出站目标")).when(fixture.outbound).validateConfig("mail.host","unapproved");assertThrows(IllegalArgumentException.class,()->fixture.management.applyDefault(2L,"config.mail.host","unapproved"));verifyNoInteractions(fixture.readiness);
 }
 @Test void newTenantRejectsUnapprovedOutboundDefaultBeforeAnyInitialization(){
  service.save(input("config.mail.host","SMTP 主机","unapproved"));TenantFixture fixture=new TenantFixture();doThrow(new IllegalArgumentException("未经授权的出站目标")).when(fixture.outbound).validateConfig("mail.host","unapproved");
  ControlController.TenantInput value=new ControlController.TenantInput();value.code="newtenant";value.name="新租户";value.reason="创建测试租户";assertThrows(IllegalArgumentException.class,()->fixture.management.create(value));verify(fixture.tenants,never()).saveAndFlush(any());verify(fixture.grants,never()).save(any());verifyNoInteractions(fixture.template,fixture.verification);
 }
 @Test void updatedChoicesRejectStaleEditsAndInvalidTenantValues(){
  service.save(input("config.example.mode","模式","normal","normal","strict"));ControlPolicyDefinitionService.Input edit=input("config.example.mode","新名字","strict","normal","strict");
  edit.version=null;assertThrows(ResponseStatusException.class,()->service.save(edit));edit.version=0L;assertEquals(1L,service.save(edit).version);assertEquals("新名字",view(edit.key).name);assertDoesNotThrow(()->service.requireAllowedValue(edit.key,"strict"));assertThrows(ResponseStatusException.class,()->service.requireAllowedValue(edit.key,"other"));assertThrows(ResponseStatusException.class,()->service.save(edit));
  assertDoesNotThrow(()->service.save(input("config.example.free","自由配置","")));assertDoesNotThrow(()->service.requireAllowedValue("config.example.free","free value"));
 }
 @Test void invalidDefinitionsCannotInventFeaturesStoreSecretsOrBypassRetention(){
  List<ControlPolicyDefinitionService.Input> invalid=Arrays.asList(
   input("feature.not_implemented","未知","false","false","true"),input("feature.option","期权","true","true"),input("feature.option","期权","maybe","false","maybe"),input("feature.option","期权","true","false"),
   input("config.mail.password","邮件密码","sensitive"),input("config.provider.credential","凭据","sensitive"),input("config.platform.control","平台配置","value"),input("feature.option","","false","false","true"),
   input("config.example.mode","模式","normal","normal","normal"),input("config.support.channel","客服渠道","internal","internal"),input("retention.auto_delete_enabled","自动删除","true","false","true"),input("retention.keep_days","保留期","7","7"));
  for(ControlPolicyDefinitionService.Input value:invalid)assertThrows(IllegalArgumentException.class,()->service.save(value),value.key);verify(repository,never()).saveAndFlush(any());verifyNoInteractions(audit);
  assertDoesNotThrow(()->service.save(input("retention.keep_days","会话历史保留期","365","365")));assertEquals("会话历史保留期",view("retention.keep_days").name);
 }
 @Test void blankTenantPolicyReasonGetsAuditDefaultAndDoesNotRelaxOtherRequiredReasons(){
  TenantFixture fixture=new TenantFixture();for(String reason:Arrays.asList(null,"","   ")){
   ControlController.PolicyInput value=fixture.input("feature.registration","false");value.reason=reason;TenantPolicy saved=fixture.management.policy(2L,value);assertEquals(2L,saved.getTenantId());assertEquals("false",saved.getValue());assertEquals("总控授权策略管理",value.reason);
  }
  verify(audit,times(3)).record(eq(7L),eq(2L),isNull(),eq("POLICY_UPDATE"),eq("feature.registration"),eq("SUCCESS"),anyString(),eq("总控授权策略管理"));verifyNoInteractions(fixture.readiness);assertThrows(IllegalArgumentException.class,()->TenantManagementService.reason(null));assertEquals("人工调整",TenantManagementService.policyReason(" 人工调整 "));
 }
 @Test void allowedRegistrationGrantStillRequiresRealFeatureReadiness(){
  TenantFixture fixture=new TenantFixture();doThrow(new AccessDeniedException("注册基础配置未齐备")).when(fixture.readiness).requireFeatureReady(2L,"registration");
  assertThrows(AccessDeniedException.class,()->fixture.management.policy(2L,fixture.input("feature.registration","true")));verify(fixture.readiness).requireFeatureReady(2L,"registration");verifyNoInteractions(audit);
 }
 @Test void unsupportedChoiceAndIndirectSupportGrantAreRejectedBeforeTenantPolicyWrite(){
  TenantFixture fixture=new TenantFixture();service.save(input("feature.option","期权","false","false"));service.save(input("feature.support","站内客服","false","false"));
  assertThrows(ResponseStatusException.class,()->fixture.management.policy(2L,fixture.input("feature.option","true")));assertThrows(ResponseStatusException.class,()->fixture.management.policy(2L,fixture.input("config.support.channel","internal")));
  assertThrows(IllegalArgumentException.class,()->fixture.management.policy(2L,fixture.input("retention.auto_delete_enabled","true")));verify(fixture.grants,never()).saveAndFlush(any());verifyNoInteractions(fixture.readiness);
 }
 @Test void realJpaCatalogRoundTripAndOptimisticVersionRejectLostUpdate(){
  org.hibernate.cfg.Configuration configuration=new org.hibernate.cfg.Configuration().addAnnotatedClass(ControlPolicyDefinition.class)
   .setProperty("hibernate.connection.driver_class","org.h2.Driver").setProperty("hibernate.connection.url","jdbc:h2:mem:policy-catalog-"+UUID.randomUUID()+";MODE=MySQL")
   .setProperty("hibernate.dialect","org.hibernate.dialect.H2Dialect").setProperty("hibernate.hbm2ddl.auto","create-drop");
  try(org.hibernate.SessionFactory factory=configuration.buildSessionFactory()){
   try(org.hibernate.Session initial=factory.openSession()){initial.beginTransaction();ControlPolicyDefinition row=new ControlPolicyDefinition();row.setKey("feature.option");row.setName("期权交易");row.setOptionsJson("[\"false\",\"true\"]");row.setDefaultValue("false");initial.persist(row);initial.getTransaction().commit();assertEquals(0L,row.getVersion());}
   try(org.hibernate.Session first=factory.openSession();org.hibernate.Session stale=factory.openSession()){
    first.beginTransaction();stale.beginTransaction();ControlPolicyDefinition current=first.find(ControlPolicyDefinition.class,"feature.option"),old=stale.find(ControlPolicyDefinition.class,"feature.option");assertEquals("期权交易",old.getName());assertEquals("false",old.getDefaultValue());
    current.setName("新的策略名字");first.getTransaction().commit();assertEquals(1L,current.getVersion());old.setName("不应覆盖");RuntimeException failure=assertThrows(RuntimeException.class,stale::flush);assertTrue(failure instanceof javax.persistence.OptimisticLockException||failure instanceof org.hibernate.StaleStateException);stale.getTransaction().rollback();
   }
   try(org.hibernate.Session read=factory.openSession()){ControlPolicyDefinition stored=read.find(ControlPolicyDefinition.class,"feature.option");assertEquals("新的策略名字",stored.getName());assertEquals("[\"false\",\"true\"]",stored.getOptionsJson());assertEquals(1L,stored.getVersion());}
  }
 }
 @Test void realJpaReadinessFailureRollsBackEntireFanoutCatalogTenantVersionsAndAudit(){
  org.hibernate.cfg.Configuration configuration=new org.hibernate.cfg.Configuration().addAnnotatedClass(ControlPolicyDefinition.class).addAnnotatedClass(Tenant.class).addAnnotatedClass(TenantPolicy.class).addAnnotatedClass(ControlAuditLog.class)
   .setProperty("hibernate.connection.driver_class","org.h2.Driver").setProperty("hibernate.connection.url","jdbc:h2:mem:policy-defaults-"+UUID.randomUUID()+";MODE=MySQL")
   .setProperty("hibernate.dialect","org.hibernate.dialect.H2Dialect").setProperty("hibernate.hbm2ddl.auto","create-drop");
  try(org.hibernate.SessionFactory factory=configuration.buildSessionFactory()){
   JpaTransactionManager manager=new JpaTransactionManager(factory);TransactionTemplate tx=new TransactionTemplate(manager);EntityManager em=SharedEntityManagerCreator.createSharedEntityManager(factory);JpaRepositoryFactory repositories=new JpaRepositoryFactory(em);
   ControlPolicyDefinitionRepository catalog=repositories.getRepository(ControlPolicyDefinitionRepository.class);TenantRepository registrations=repositories.getRepository(TenantRepository.class);TenantPolicyRepository grants=repositories.getRepository(TenantPolicyRepository.class);
   ControlAuditService realAudit=transactional(new ControlAuditService(repositories.getRepository(ControlAuditLogRepository.class)),manager);TenantReadinessService readiness=mock(TenantReadinessService.class);
   ControlPolicyDefinitionService catalogTarget=new ControlPolicyDefinitionService(catalog,realAudit),catalogService=transactional(catalogTarget,manager);
   TenantManagementService target=new TenantManagementService(registrations,grants,mock(TenantDomainHistoryRepository.class),mock(TenantHostService.class),realAudit,mock(TenantSafeTemplate.class),mock(TenantDomainVerification.class));TenantManagementService tenantService=transactional(target,manager);
   org.springframework.beans.factory.ObjectProvider<TenantManagementService> lazy=mock(org.springframework.beans.factory.ObjectProvider.class);when(lazy.getObject()).thenReturn(tenantService);ReflectionTestUtils.setField(catalogTarget,"tenants",registrations);ReflectionTestUtils.setField(catalogTarget,"management",lazy);ReflectionTestUtils.setField(target,"definitions",catalogService);ReflectionTestUtils.setField(target,"readiness",readiness);ReflectionTestUtils.setField(target,"outbound",mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class));
   Tenant draft=new Tenant(),active=new Tenant();draft.setCode("default-draft");draft.setName("草稿租户");active.setCode("default-active");active.setName("正常租户");active.setStatus("ACTIVE");active.setConfigReady(true);active.setDomainVerified(true);
   tx.executeWithoutResult(status->{em.persist(stored.get("feature.registration"));em.persist(draft);em.persist(active);});
   doThrow(new AccessDeniedException("注册基础配置未齐备")).when(readiness).requireFeatureReady(active.getId(),"registration");
   ControlPolicyDefinitionService.Input change=input("feature.registration","自动注册授权","true","false","true");assertThrows(AccessDeniedException.class,()->catalogService.save(change));
   tx.executeWithoutResult(status->{ControlPolicyDefinition original=em.find(ControlPolicyDefinition.class,change.key);assertEquals("false",original.getDefaultValue());assertEquals(0L,original.getVersion());assertEquals(0L,em.createQuery("select count(p) from TenantPolicy p",Long.class).getSingleResult());assertEquals(0L,em.createQuery("select count(a) from ControlAuditLog a",Long.class).getSingleResult());assertEquals(0L,em.find(Tenant.class,draft.getId()).getPolicyVersion());assertEquals(0L,em.find(Tenant.class,active.getId()).getPolicyVersion());});
   doNothing().when(readiness).requireFeatureReady(active.getId(),"registration");ControlPolicyDefinitionService.View result=catalogService.save(change);assertEquals(2,result.appliedTenants);assertEquals(0,result.retainedTenants);assertEquals(1L,result.version);
   tx.executeWithoutResult(status->{for(Tenant tenant:Arrays.asList(draft,active)){TenantPolicy grant=grants.findByTenantIdAndKey(tenant.getId(),"feature.registration").orElseThrow(AssertionError::new);assertEquals("true",grant.getValue());assertTrue(grant.isLocked());assertEquals(1L,em.find(Tenant.class,tenant.getId()).getPolicyVersion());}assertEquals(3L,em.createQuery("select count(a) from ControlAuditLog a",Long.class).getSingleResult());assertEquals("DRAFT",em.find(Tenant.class,draft.getId()).getStatus());assertFalse(em.find(Tenant.class,draft.getId()).isConfigReady());});
   change.defaultValue="false";change.version=result.version;result=catalogService.save(change);assertEquals(0,result.appliedTenants);assertEquals(2,result.retainedTenants);
   ControlController.TenantInput next=new ControlController.TenantInput();next.code="next-default";next.name="新默认租户";next.reason="创建默认授权测试";Tenant created=tenantService.create(next);assertEquals("DRAFT",created.getStatus());assertFalse(created.isConfigReady());
   tx.executeWithoutResult(status->{assertEquals("false",grants.findByTenantIdAndKey(created.getId(),"feature.registration").orElseThrow(AssertionError::new).getValue());assertEquals("true",grants.findByTenantIdAndKey(active.getId(),"feature.registration").orElseThrow(AssertionError::new).getValue());assertEquals(5L,em.createQuery("select count(a) from ControlAuditLog a",Long.class).getSingleResult());});
  }
 }
 @SuppressWarnings("unchecked") private static <T> T transactional(T target,JpaTransactionManager manager){ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)proxy.getProxy();}
 @Test void newTenantReceivesCurrentDefaultsButStaysDraftAndNeverCopiesPrivateData(){
  service.save(input("feature.registration","用户注册","true","false","true"));service.save(input("config.example.mode","模式","strict","normal","strict"));service.save(input("config.support.channel","客服渠道","internal","off","internal","external"));
  clearInvocations(repository);TenantFixture fixture=new TenantFixture();when(fixture.tenants.saveAndFlush(any())).thenAnswer(call->{Tenant tenant=call.getArgument(0);tenant.setId(3L);fixture.registrations.put(3L,tenant);return tenant;});
  ControlController.TenantInput value=new ControlController.TenantInput();value.code="newtenant";value.name="新租户";value.reason="创建测试租户";Tenant created=fixture.management.create(value);assertEquals("DRAFT",created.getStatus());assertFalse(created.isConfigReady());assertFalse(created.isDomainVerified());
  verify(fixture.grants,times(TenantPolicyService.FEATURES.size()+2)).save(argThat(grant->grant.getTenantId().equals(3L)&&grant.isLocked()));assertEquals("true",fixture.get(3L,"feature.registration").getValue());assertEquals("strict",fixture.get(3L,"config.example.mode").getValue());assertEquals("internal",fixture.get(3L,"config.support.channel").getValue());assertEquals("true",fixture.get(3L,"feature.support").getValue());assertEquals("false",fixture.get(3L,"feature.external_support").getValue());assertNull(fixture.get(3L,"retention.auto_delete_enabled"));verify(fixture.template).initialize(3L);verify(repository).lockDefaults();verifyNoInteractions(fixture.readiness,fixture.verification);
 }
 class TenantFixture {
  TenantRepository tenants=mock(TenantRepository.class);TenantPolicyRepository grants=mock(TenantPolicyRepository.class);TenantReadinessService readiness=mock(TenantReadinessService.class);TenantManagementService management;
  TenantSafeTemplate template=mock(TenantSafeTemplate.class);TenantDomainVerification verification=mock(TenantDomainVerification.class);com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound=mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);
  Map<Long,Tenant> registrations=new LinkedHashMap<>();Map<String,TenantPolicy> values=new HashMap<>();
  TenantFixture(){add(2L,"ACTIVE");when(tenants.lock(anyLong())).thenAnswer(call->Optional.ofNullable(registrations.get(call.getArgument(0))));when(tenants.findById(anyLong())).thenAnswer(call->Optional.ofNullable(registrations.get(call.getArgument(0))));when(tenants.findAll()).thenAnswer(call->new ArrayList<>(registrations.values()));
   when(grants.findByTenantIdAndKey(anyLong(),anyString())).thenAnswer(call->Optional.ofNullable(get(call.getArgument(0),call.getArgument(1))));when(grants.saveAndFlush(any())).thenAnswer(call->put(call.getArgument(0)));when(grants.save(any())).thenAnswer(call->put(call.getArgument(0)));
   management=new TenantManagementService(tenants,grants,mock(TenantDomainHistoryRepository.class),mock(TenantHostService.class),audit,template,verification);ReflectionTestUtils.setField(management,"definitions",service);ReflectionTestUtils.setField(management,"readiness",readiness);ReflectionTestUtils.setField(management,"outbound",outbound);
  }
  void add(Long id,String status){Tenant tenant=new Tenant();tenant.setId(id);tenant.setStatus(status);tenant.setConfigReady(true);tenant.setDomainVerified(true);registrations.put(id,tenant);}
  Tenant tenant(Long id){return registrations.get(id);}
  void connectDefaults(){ReflectionTestUtils.setField(service,"tenants",tenants);when(provider.getObject()).thenReturn(management);}
  TenantPolicy get(Long id,String key){return values.get(id+":"+key);}
  TenantPolicy put(TenantPolicy value){values.put(value.getTenantId()+":"+value.getKey(),value);return value;}
  TenantPolicy grant(Long id,String key,String value,boolean locked){TenantPolicy row=new TenantPolicy();row.setTenantId(id);row.setKey(key);row.setValue(value);row.setLocked(locked);return put(row);}
  ControlController.PolicyInput input(String key,String value){ControlController.PolicyInput in=new ControlController.PolicyInput();in.key=key;in.value=value;in.locked=true;return in;}
 }
}
