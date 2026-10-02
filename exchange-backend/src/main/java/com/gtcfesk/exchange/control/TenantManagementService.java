package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
@Service @RequiredArgsConstructor
public class TenantManagementService {
 private final TenantRepository tenants;private final TenantPolicyRepository policies;private final TenantDomainHistoryRepository history;private final TenantHostService hosts;private final ControlAuditService audit;
 private final TenantSafeTemplate template;
 @org.springframework.beans.factory.annotation.Autowired private TenantReadinessService readiness;
 @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound;
 private static final Set<String> STATES=new HashSet<>(Arrays.asList("DRAFT","ACTIVE","STOP_NEW","MAINTENANCE","DISABLED"));
 @Transactional public Tenant create(ControlController.TenantInput input){
  ControlIdentity.actorId();reason(input.reason);if(input.code==null||!input.code.matches("[a-z0-9_-]{2,64}"))throw new IllegalArgumentException("租户编号无效");
  Tenant tenant=new Tenant();tenant.setCode(input.code);tenant.setName(name(input.name));tenants.saveAndFlush(tenant);template.initialize(tenant.getId());if(input.frontendHost!=null&&!input.frontendHost.trim().isEmpty())verification.prepare(tenant.getId(),input.frontendHost,input.reason);
  for(String feature:TenantPolicyService.FEATURES){TenantPolicy p=new TenantPolicy();p.setTenantId(tenant.getId());p.setKey("feature."+feature);p.setValue("false");p.setLocked(true);policies.save(p);}
  // Safe template contains no copied business records, credentials, funding addresses or provider keys.
  audit.record(ControlIdentity.actorId(),tenant.getId(),null,"TENANT_CREATE",String.valueOf(tenant.getId()),"SUCCESS","template=safe-v1;status=DRAFT;disabledSymbols=BTCUSD;disabledProducts=SAFE-V1-DISABLED",input.reason);return tenant;
 }
 @Transactional public Tenant update(Long id,ControlController.TenantInput input){
  ControlIdentity.actorId();Tenant t=tenants.lock(id).orElseThrow(ControlService::invalid);reason(input.reason);String previous=t.getStatus();
  if(input.name!=null)t.setName(name(input.name));
  if(input.frontendHost!=null&&!input.frontendHost.equals(t.getFrontendHost()))throw new IllegalArgumentException("域名变更必须通过prepare、verify、activate三步骤；现用域名不变");
  if(Boolean.TRUE.equals(input.configReady)||"ACTIVE".equals(input.status))readiness.requireReady(id);
  if(input.configReady!=null)t.setConfigReady(input.configReady);
  if(input.status!=null){if(!STATES.contains(input.status))throw new IllegalArgumentException("状态无效");if("ACTIVE".equals(input.status)&&(!t.isDomainVerified()||!t.isConfigReady()))throw new IllegalArgumentException("域名验证和配置确认未完成");if("DISABLED".equals(input.status)&&!"DISABLED".equals(previous))t.setSessionVersion(t.getSessionVersion()+1);t.setStatus(input.status);}
  t.setPolicyVersion(t.getPolicyVersion()+1);audit.record(ControlIdentity.actorId(),id,null,"TENANT_UPDATE",String.valueOf(id),"SUCCESS","status:"+previous+"->"+t.getStatus(),input.reason);return t;
 }
 @Transactional public TenantPolicy policy(Long id,ControlController.PolicyInput input){
  reason(input.reason);Tenant t=tenants.lock(id).orElseThrow(ControlService::invalid);
  if(input.key==null||input.key.length()>128||!(input.key.startsWith("config.")||input.key.startsWith("feature."))||input.value==null||input.value.length()>8192)throw new IllegalArgumentException("策略无效");
  if(input.key.startsWith("config.")&&com.gtcfesk.exchange.tenant.TenantSecrets.secret(input.key))throw new IllegalArgumentException("密钥请通过租户加密配置入口维护，不支持明文策略覆盖");
  if(input.key.startsWith("feature.")&&(!TenantPolicyService.FEATURES.contains(input.key.substring(8))||!Arrays.asList("true","false").contains(input.value)))throw new IllegalArgumentException("功能策略无效");
  if("config.support.channel".equals(input.key)&&!Arrays.asList("off","internal","external").contains(input.value))throw new IllegalArgumentException("客服渠道无效");
  if("feature.support".equals(input.key)&&"false".equals(input.value)&&policies.findByTenantIdAndKey(id,"config.support.channel").map(p->p.isLocked()&&"internal".equals(p.getValue())).orElse(false))throw new IllegalArgumentException("请先解除强制站内客服策略");
  if("feature.external_support".equals(input.key)&&"true".equals(input.value)&&policies.findByTenantIdAndKey(id,"config.support.channel").map(p->p.isLocked()&&"internal".equals(p.getValue())).orElse(false))throw new IllegalArgumentException("客服渠道已锁定站内");
  TenantPolicy p=policies.findByTenantIdAndKey(id,input.key).orElseGet(TenantPolicy::new);p.setTenantId(id);p.setKey(input.key);p.setValue(input.value);p.setLocked(Boolean.TRUE.equals(input.locked));policies.saveAndFlush(p);
  if("config.support.channel".equals(input.key)&&Boolean.TRUE.equals(input.locked)&&"internal".equals(input.value)){
   for(String feature:Arrays.asList("support","external_support")){TenantPolicy f=policies.findByTenantIdAndKey(id,"feature."+feature).orElseGet(TenantPolicy::new);f.setTenantId(id);f.setKey("feature."+feature);f.setValue("support".equals(feature)?"true":"false");f.setLocked(true);policies.save(f);}
  }
  if("config.support.channel".equals(input.key)&&Boolean.TRUE.equals(input.locked)&&"internal".equals(input.value))readiness.requireFeatureReady(id,"support");
  if(input.key.startsWith("config."))outbound.validateConfig(input.key.substring(7),input.value);
  if(input.key.startsWith("feature.")&&"true".equals(input.value))readiness.requireFeatureReady(id,input.key.substring(8));
  if("ACTIVE".equals(t.getStatus())&&!(input.key.startsWith("feature.")&&"false".equals(input.value)))readiness.requireReady(id);
  t.setPolicyVersion(t.getPolicyVersion()+1);
  audit.record(ControlIdentity.actorId(),id,null,"POLICY_UPDATE",input.key,"SUCCESS","policyVersion="+t.getPolicyVersion()+";locked="+p.isLocked(),input.reason);return p;
 }
 public Object verifyDomain(Long id){List<Map<String,Object>> pending=verification.candidates(id);if(pending.size()!=1)throw new IllegalArgumentException("请先准备唯一候选域名");Map<String,Object> b=pending.get(0);return verification.verify(id,b.get("hostname").toString(),((Number)b.get("version")).longValue());}
 private final TenantDomainVerification verification;
 static void reason(String value){if(value==null||value.trim().length()<3||value.length()>512)throw new IllegalArgumentException("请填写操作原因（3 至 512 字符）");}
 private static String name(String value){if(value==null||value.trim().isEmpty()||value.length()>128)throw new IllegalArgumentException("名称无效");return value.trim();}
}
