package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.util.*;
@Service @RequiredArgsConstructor
public class TenantPolicyService {
 public static final List<String> FEATURES=Collections.unmodifiableList(Arrays.asList("registration","option","contract","financial","loan","activity","simulation","deposit","withdraw","support","external_support","inbox","agent"));
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationEnvironment simulation;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationGateway gateway;
 @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound;
 @org.springframework.beans.factory.annotation.Autowired private TenantReadinessService readiness;
 private final TenantRepository tenants; private final TenantPolicyRepository policies;
 @javax.persistence.PersistenceContext private javax.persistence.EntityManager em;
 /** Readers share the existing tenant anchor; writers already lock it before policy/config changes.
  * Call only after user/funding locks. No tenant authority writer acquires trading funding locks. */
 public Tenant lockCurrentTenant(){return lockCurrentTenant(false);}
 public Tenant lockCurrentTenantForWrite(){return lockCurrentTenant(true);}
 private Tenant lockCurrentTenant(boolean write){
  if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Current tenant authority requires the caller transaction");
  org.hibernate.query.NativeQuery<?> query=em.createNativeQuery("SELECT * FROM tenant WHERE id=?1 "+(write?"FOR UPDATE":"LOCK IN SHARE MODE")).unwrap(org.hibernate.query.NativeQuery.class);
  query.addEntity("locked",Tenant.class,org.hibernate.LockMode.NONE);query.setParameter(1,TenantContext.requireTenantId());
  query.setFlushMode(javax.persistence.FlushModeType.COMMIT);
  List<?> rows=query.getResultList();if(rows.size()!=1)throw new AccessDeniedException("租户不可用");
  Tenant tenant=(Tenant)rows.get(0);if(write)em.flush();em.refresh(tenant,write?javax.persistence.LockModeType.PESSIMISTIC_WRITE:javax.persistence.LockModeType.PESSIMISTIC_READ);return tenant;
 }
 /** Funding facades use READ_COMMITTED: readiness scalar queries observe this authorization point. */
 public void requireCurrentNewBusiness(String feature){
  lockCurrentTenant();
  org.hibernate.query.NativeQuery<?> policy=em.createNativeQuery("SELECT * FROM tenant_policy WHERE tenant_id=?1 ORDER BY id LOCK IN SHARE MODE").unwrap(org.hibernate.query.NativeQuery.class);
  policy.addEntity("locked",TenantPolicy.class,org.hibernate.LockMode.NONE);policy.setParameter(1,TenantContext.requireTenantId());
  for(Object row:policy.getResultList())em.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_READ);
  // Existing readiness uses entity values for these trade keys. Refresh them under the same shared anchor.
  org.hibernate.query.NativeQuery<?> config=em.createNativeQuery("SELECT * FROM system_config WHERE tenant_id=?1 AND config_key IN ('site.name','system.timezone','market.quote.token') ORDER BY id LOCK IN SHARE MODE").unwrap(org.hibernate.query.NativeQuery.class);
  config.addEntity("locked",com.gtcfesk.exchange.entity.SystemConfig.class,org.hibernate.LockMode.NONE);config.setParameter(1,TenantContext.requireTenantId());
  for(Object row:config.getResultList())em.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_READ);
  requireNewBusiness(feature);
 }
 public Tenant current(){return tenants.findById(TenantContext.requireTenantId()).orElseThrow(()->new AccessDeniedException("租户不可用"));}
 public void requireLogin(Long tenant){Tenant t=tenants.findById(tenant).orElseThrow(()->new AccessDeniedException("租户不可用"));if("DISABLED".equals(t.getStatus()))throw new AccessDeniedException("租户已停用");}
 /** Existing settlement, history and controlled exits never call this new-business guard. */
 public void requireNewBusiness(String feature){
  if(!FEATURES.contains(feature))throw new IllegalArgumentException("未知功能");
  if(simulation!=null&&simulation.enabled()){requireSimulationBusiness(feature);return;}
  Tenant t=current();
  if(!"ACTIVE".equals(t.getStatus())||!t.isConfigReady()||!t.isDomainVerified())throw new AccessDeniedException("租户暂不接受新增业务");
  if(!policies.findByTenantIdAndKey(t.getId(),"feature."+feature).map(p->"true".equals(p.getValue())).orElse(false))throw new AccessDeniedException("功能未获授权");
  readiness.requireFeatureReady(t.getId(),feature);
 }
 private void requireSimulationBusiness(String feature){
  org.springframework.web.context.request.RequestAttributes attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
  if(!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes))throw new AccessDeniedException("模拟新增业务缺少实时身份");
  String bearer=((org.springframework.web.context.request.ServletRequestAttributes)attributes).getRequest().getHeader("Authorization");
  if(bearer==null||!bearer.startsWith("Bearer "))throw new AccessDeniedException("模拟新增业务缺少实时身份");
  Map<String,Object> session=gateway.get("/session",bearer);Object features=session.get("features");
  if(!Boolean.TRUE.equals(session.get("acceptNewBusiness"))||!(features instanceof Map)||!Boolean.TRUE.equals(((Map<?,?>)features).get("simulation"))||!Boolean.TRUE.equals(((Map<?,?>)features).get(feature)))throw new AccessDeniedException("真实租户未授权模拟新增业务");
 }
 public boolean featureEnabled(String feature){return policies.findByTenantIdAndKey(TenantContext.requireTenantId(),"feature."+feature).map(p->"true".equals(p.getValue())).orElse(false);}
 public void requireConfigChange(String key,String value){
  outbound.validateConfig(key,value);
  if("support.settings".equals(key)){com.gtcfesk.exchange.support.SupportSettings.Settings settings=com.gtcfesk.exchange.support.SupportSettings.parse(value);requireConfigChange("support.channel",settings.mode);if("external".equals(settings.mode)&&!featureEnabled("external_support")||"internal".equals(settings.mode)&&!featureEnabled("support")||settings.inboxEnabled&&!featureEnabled("inbox"))throw new AccessDeniedException("功能未获授权");}
  if("customer.service.link".equals(key)&&value!=null&&!value.trim().isEmpty()&&"internal".equals(effectiveConfig("support.channel",null)))throw new AccessDeniedException("外部客服已禁止");
  if(key==null||key.startsWith("platform.")||key.startsWith("feature."))throw new AccessDeniedException("配置不可修改");
  TenantPolicy p=policies.findByTenantIdAndKey(TenantContext.requireTenantId(),"config."+key).orElse(null);
  if(p!=null&&("DENY".equals(p.getValue())||(p.isLocked()&&!Objects.equals(value,p.getValue()))))throw new AccessDeniedException("配置已由总控锁定");
 }
 public String effectiveConfig(String key,String stored){return policies.findByTenantIdAndKey(TenantContext.requireTenantId(),"config."+key).filter(TenantPolicy::isLocked).map(TenantPolicy::getValue).orElse(stored);}
}
