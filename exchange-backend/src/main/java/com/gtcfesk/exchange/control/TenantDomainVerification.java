package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.security.OutboundEndpointPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
/** Per-role reservations; both roles share one tenant lock and an explicit configuration version. */
@Service @RequiredArgsConstructor
public class TenantDomainVerification {
 private final TenantRepository tenants;private final ControlAuditService audit;
 private final TenantDomainBindingRepository bindings;private final TenantDomainHistoryRepository history;
 private final TenantHostService hosts;private final OutboundEndpointPolicy outbound;private final PlatformTransactionManager manager;
 private static final List<String> CANDIDATES=Arrays.asList("PENDING","VERIFIED");
 public static class Selection {public String role,hostname;public Long version;}
 private long actor(){
  org.springframework.security.core.Authentication a=SecurityContextHolder.getContext().getAuthentication();ControlIdentity i=ControlIdentity.current();
  if(a==null||!a.isAuthenticated()||a.getAuthorities().stream().noneMatch(r->"ROLE_CONTROL".equals(r.getAuthority()))||i==null||i.getActorId()==null||i.getActorId()<=0||i.getTenantId()!=null||i.getAccessSessionId()!=null||TenantContext.currentTenantId()!=null)throw new AccessDeniedException("需要独立总控身份");return i.getActorId();
 }
 private static String role(String role){if(!Arrays.asList("ENTRY","FRONTEND").contains(role))throw new IllegalArgumentException("域名角色无效");return role;}
 private static void version(Tenant t,long expected){if(t.getDomainVersion()!=expected)throw new IllegalArgumentException("域名配置已变更，请刷新后重试");}
 private String activeHost(Tenant t,String role){return "ENTRY".equals(role)?t.getEntryHost():t.getFrontendHost();}
 private boolean verified(Tenant t,String role){return "ENTRY".equals(role)?t.isEntryVerified():t.isDomainVerified();}
 private Map<String,Object> view(TenantDomainBinding b){Map<String,Object> v=new LinkedHashMap<>();v.put("hostname",b.getHostname());v.put("tenantId",b.getTenantId());v.put("role",b.getRole());v.put("status",b.getStatus());v.put("version",b.getVersion());v.put("expiresAt",b.getExpiresAt());v.put("verifiedAt",b.getVerifiedAt());return v;}
 private Map<String,Object> state(Tenant t){
  Map<String,Object> v=new LinkedHashMap<>();v.put("tenantId",t.getId());v.put("entryHost",t.getEntryHost());v.put("frontendHost",t.getFrontendHost());v.put("entryEnabled",t.isEntryEnabled());v.put("entryVerified",t.isEntryVerified());v.put("domainVerified",t.isDomainVerified());v.put("domainVersion",t.getDomainVersion());
  List<Map<String,Object>> rows=new ArrayList<>();for(TenantDomainBinding b:bindings.findByTenantIdOrderByRoleAscHostnameAsc(t.getId()))rows.add(view(b));v.put("bindings",rows);return v;
 }
 @Transactional(readOnly=true) public Map<String,Object> state(Long id){actor();return state(tenants.findById(id).orElseThrow(ControlService::invalid));}
 @Transactional(readOnly=true) public List<Map<String,Object>> candidates(Long id){actor();tenants.findById(id).orElseThrow(ControlService::invalid);List<Map<String,Object>> rows=new ArrayList<>();for(TenantDomainBinding b:bindings.findByTenantIdAndStatusIn(id,CANDIDATES))rows.add(view(b));return rows;}
 @Transactional public Map<String,Object> prepare(Long id,String raw,String reason){return prepare(id,"FRONTEND",raw,null,reason);}
 @Transactional public Map<String,Object> prepare(Long id,String role,String raw,Long expected,String reason){
  long actor=actor();TenantManagementService.reason(reason);Tenant t=tenants.lock(id).orElseThrow(ControlService::invalid);if(expected!=null)version(t,expected);
  TenantDomainBinding b=reserve(t,role(role),raw);t.setDomainVersion(t.getDomainVersion()+1);tenants.saveAndFlush(t);
  audit.record(actor,id,null,"DOMAIN_PREPARE",b.getHostname(),"SUCCESS","role="+role+";active hosts unchanged;ttl=900s;domainVersion="+t.getDomainVersion(),reason);return view(b);
 }
 @Transactional public Map<String,Object> prepareChange(Long id,String entry,String frontend,long expected,String reason){
  long actor=actor();TenantManagementService.reason(reason);Tenant t=tenants.lock(id).orElseThrow(ControlService::invalid);version(t,expected);boolean changed=false;
  for(String r:Arrays.asList("ENTRY","FRONTEND")){
   String raw="ENTRY".equals(r)?entry:frontend;if(raw==null)continue;
   if(raw.isEmpty()){if(activeHost(t,r)!=null)throw new IllegalArgumentException("现用域名不能用空值解绑；入口可独立关闭");continue;}
   String host=hosts.validateAssignment(raw,r);
   if(host.equals(activeHost(t,r))&&verified(t,r)){for(TenantDomainBinding b:bindings.findByTenantIdAndRoleAndStatusIn(id,r,CANDIDATES)){retire(b);changed=true;}}
   else{reserve(t,r,host);changed=true;}
  }
  if(!changed)throw new IllegalArgumentException("没有待准备的域名变更");
  t.setDomainVersion(t.getDomainVersion()+1);tenants.saveAndFlush(t);audit.record(actor,id,null,"DOMAIN_PREPARE",String.valueOf(id),"SUCCESS","role-scoped candidates;active hosts unchanged;domainVersion="+t.getDomainVersion(),reason);return state(t);
 }
 private TenantDomainBinding reserve(Tenant t,String role,String raw){
  Long id=t.getId();String host=hosts.validateAssignment(raw,role);
  Tenant taken=tenants.findByFrontendHost(host).orElse(null),entryTaken=tenants.findByEntryHost(host).orElse(null);
  if(taken!=null&&!id.equals(taken.getId())||entryTaken!=null&&!id.equals(entryTaken.getId()))throw new IllegalArgumentException("域名不可用");
  TenantDomainBinding b=bindings.lock(host).orElse(null);
  if(b==null&&history.findByHostname(host).isPresent())throw new IllegalArgumentException("域名已退役，须先审查释放");
  if(b!=null&&!(id.equals(b.getTenantId())&&role.equals(b.getRole())&&!"RETIRED".equals(b.getStatus())||"RELEASED".equals(b.getStatus())))throw new IllegalArgumentException("域名被占用或已退役");
  if(host.equals(activeHost(t,role))&&verified(t,role))throw new IllegalArgumentException("该角色域名已生效");
  for(TenantDomainBinding old:bindings.findByTenantIdAndRoleAndStatusIn(id,role,CANDIDATES))if(!host.equals(old.getHostname()))retire(old);
  bindings.flush(); // Free the unique candidate slot before reserving its replacement.
  if(b==null){b=new TenantDomainBinding();b.setHostname(host);}b.setTenantId(id);b.setRole(role);b.setStatus("PENDING");b.setChallenge(UUID.randomUUID().toString().replace("-",""));b.setExpiresAt(Instant.now().plusSeconds(900));b.setVerifiedAt(null);return bindings.saveAndFlush(b);
 }
 private void candidate(TenantDomainBinding b,Long id,String role,long version,String status){if(!id.equals(b.getTenantId())||!role.equals(b.getRole())||b.getVersion()!=version||!status.equals(b.getStatus())||b.getExpiresAt()==null||!Instant.now().isBefore(b.getExpiresAt()))throw new IllegalArgumentException("候选域名已变更或验证已过期，请重新准备");}
 public Map<String,Object> verify(Long id,String raw,long version){return verify(id,"FRONTEND",raw,version,null);}
 public Map<String,Object> verify(Long id,String role,String raw,long bindingVersion,Long expected){
  long actor=actor();role(role);String host=hosts.validateAssignment(raw,role);Tenant t=tenants.findById(id).orElseThrow(ControlService::invalid);long snapshotVersion=expected==null?t.getDomainVersion():expected;version(t,snapshotVersion);
  TenantDomainBinding snapshot=bindings.findById(host).orElseThrow(()->new IllegalArgumentException("须先准备候选域名"));candidate(snapshot,id,role,bindingVersion,"PENDING");String challenge=snapshot.getChallenge();
  // DNS/TLS probe runs without a DB lock. Recheck both versions after it returns.
  try{JsonNode reply=new ObjectMapper().readTree(outbound.routingCheck(host,challenge));if(!reply.path("tenantId").isIntegralNumber()||reply.path("tenantId").asLong()!=id||!challenge.equals(reply.path("challenge").asText())||!role.equals(reply.path("role").asText()))throw new IllegalArgumentException("路由挑战不匹配");}
  catch(Exception failure){audit.failure(actor,id,null,"DOMAIN_VERIFY",host);throw new IllegalArgumentException("HTTPS证书、挑战或租户路由核验失败；当前域名未变更");}
  return new TransactionTemplate(manager).execute(tx->{Tenant locked=tenants.lock(id).orElseThrow(ControlService::invalid);version(locked,snapshotVersion);TenantDomainBinding b=bindings.lock(host).orElseThrow(ControlService::invalid);candidate(b,id,role,bindingVersion,"PENDING");if(!challenge.equals(b.getChallenge()))throw new IllegalArgumentException("挑战已变更");b.setStatus("VERIFIED");b.setVerifiedAt(Instant.now());bindings.saveAndFlush(b);audit.record(actor,id,null,"DOMAIN_CANDIDATE_VERIFIED",host,"SUCCESS","role="+role+";TLS hostname and nonce-bound tenant route checked",null);return view(b);});
 }
 @Transactional public Map<String,Object> activate(Long id,String raw,long bindingVersion,String reason){
  long actor=actor();TenantManagementService.reason(reason);Tenant t=tenants.lock(id).orElseThrow(ControlService::invalid);String host=hosts.validateAssignment(raw);TenantDomainBinding b=bindings.lock(host).orElseThrow(ControlService::invalid);
  if(id.equals(b.getTenantId())&&"FRONTEND".equals(b.getRole())&&"ACTIVE".equals(b.getStatus())&&host.equals(t.getFrontendHost())&&t.isDomainVerified())return view(b);
  List<TenantDomainBinding> pending=bindings.findByTenantIdAndStatusIn(id,CANDIDATES);if(pending.size()!=1)throw new IllegalArgumentException("须一次原子激活全部角色候选");
  candidate(b,id,"FRONTEND",bindingVersion,"VERIFIED");switchHost(t,b);finish(t,actor,reason);return view(b);
 }
 @Transactional public Map<String,Object> activateChange(Long id,long expected,List<Selection> selected,Boolean enabled,String reason){
  long actor=actor();TenantManagementService.reason(reason);Tenant t=tenants.lock(id).orElseThrow(ControlService::invalid);version(t,expected);
  List<TenantDomainBinding> pending=bindings.findByTenantIdAndStatusIn(id,CANDIDATES);
  if(selected==null||pending.isEmpty()||pending.size()!=selected.size()||selected.size()>2)throw new IllegalArgumentException("须一次提交全部角色候选及版本");
  Set<String> roles=new HashSet<>();List<TenantDomainBinding> checked=new ArrayList<>();
  for(Selection s:selected){if(s==null||s.version==null||!roles.add(role(s.role)))throw new IllegalArgumentException("角色与候选版本无效");String host=hosts.validateAssignment(s.hostname,s.role);TenantDomainBinding b=bindings.lock(host).orElseThrow(ControlService::invalid);candidate(b,id,s.role,s.version,"VERIFIED");if(pending.stream().noneMatch(p->p.getHostname().equals(host)))throw new IllegalArgumentException("候选集合已变化");checked.add(b);}
  for(TenantDomainBinding b:checked)switchHost(t,b);
  if(enabled!=null)t.setEntryEnabled(enabled);if(t.isEntryEnabled())requireEntryReady(t);
  finish(t,actor,reason);return state(t);
 }
 private void switchHost(Tenant t,TenantDomainBinding b){
  String previous=activeHost(t,b.getRole());
  if(previous!=null&&!previous.equals(b.getHostname())){TenantDomainBinding old=bindings.lock(previous).orElseThrow(()->new IllegalArgumentException("原域名登记不完整，禁止切换"));if(!t.getId().equals(old.getTenantId())||!b.getRole().equals(old.getRole())||!"ACTIVE".equals(old.getStatus()))throw new IllegalArgumentException("原域名归属或角色异常");retire(old);bindings.flush();}
  b.setStatus("ACTIVE");b.setChallenge(null);b.setExpiresAt(null);bindings.saveAndFlush(b);
  if("ENTRY".equals(b.getRole())){t.setEntryHost(b.getHostname());t.setEntryVerified(true);}else{t.setFrontendHost(b.getHostname());t.setDomainVerified(true);}
 }
 private void retire(TenantDomainBinding b){
  b.setStatus("RETIRED");b.setChallenge(null);b.setExpiresAt(null);bindings.save(b);
  if(!history.findByHostname(b.getHostname()).isPresent()){TenantDomainHistory h=new TenantDomainHistory();h.setHostname(b.getHostname());h.setTenantId(b.getTenantId());h.setRole(b.getRole());history.save(h);}
 }
 private void finish(Tenant t,long actor,String reason){
  if(t.isEntryEnabled())requireEntryReady(t);t.setDomainVersion(t.getDomainVersion()+1);t.setPolicyVersion(t.getPolicyVersion()+1);tenants.saveAndFlush(t);
  audit.record(actor,t.getId(),null,"DOMAIN_ACTIVATE",String.valueOf(t.getId()),"SUCCESS","entry="+Objects.toString(t.getEntryHost(),"")+";frontend="+Objects.toString(t.getFrontendHost(),"")+";entryEnabled="+t.isEntryEnabled()+";domainVersion="+t.getDomainVersion(),reason);
 }
 @Transactional public Map<String,Object> setEntryEnabled(Long id,boolean enabled,long expected,String reason){
  long actor=actor();TenantManagementService.reason(reason);Tenant t=tenants.lock(id).orElseThrow(ControlService::invalid);version(t,expected);if(enabled)requireEntryReady(t);
  t.setEntryEnabled(enabled);t.setDomainVersion(t.getDomainVersion()+1);t.setPolicyVersion(t.getPolicyVersion()+1);tenants.saveAndFlush(t);
  audit.record(actor,id,null,"DOMAIN_ENTRY_SWITCH",Objects.toString(t.getEntryHost(),"unconfigured"),"SUCCESS","entryEnabled="+enabled+";domainVersion="+t.getDomainVersion()+";sessions unchanged",reason);return state(t);
 }
 private boolean activeBinding(Tenant t,String role){String host=activeHost(t,role);if(host==null||!verified(t,role))return false;TenantDomainBinding b=bindings.findById(host).orElse(null);return b!=null&&t.getId().equals(b.getTenantId())&&role.equals(b.getRole())&&"ACTIVE".equals(b.getStatus())&&b.getVerifiedAt()!=null;}
 private boolean accessible(Tenant t){return t.isConfigReady()&&Arrays.asList("ACTIVE","STOP_NEW","MAINTENANCE").contains(t.getStatus());}
 private void requireEntryReady(Tenant t){if(!accessible(t)||!activeBinding(t,"ENTRY")||!activeBinding(t,"FRONTEND"))throw new IllegalArgumentException("开启入口需要同租户两类现用域名均核验且租户允许访问");}
 @Transactional(readOnly=true) public String entryTarget(String host){Tenant t=tenants.findByEntryHost(host).orElseThrow(()->new AccessDeniedException("入口不可用"));if(!t.isEntryEnabled())throw new AccessDeniedException("入口不可用");try{requireEntryReady(t);return hosts.validateAssignment(t.getFrontendHost(),"FRONTEND");}catch(IllegalArgumentException e){throw new AccessDeniedException("入口不可用");}}
 @Transactional public Map<String,Object> release(Long id,String raw,long version,String reason){actor();TenantDomainBinding b=bindings.findById(hosts.normalizeHost(raw)).orElseThrow(ControlService::invalid);return release(id,b.getRole(),raw,version,reason);}
 @Transactional public Map<String,Object> release(Long id,String role,String raw,long version,String reason){
  long actor=actor();TenantManagementService.reason(reason);tenants.lock(id).orElseThrow(ControlService::invalid);String host=hosts.validateAssignment(raw,role(role));TenantDomainBinding b=bindings.lock(host).orElseThrow(ControlService::invalid);
  if(!id.equals(b.getTenantId())||!role.equals(b.getRole())||version!=b.getVersion()||!"RETIRED".equals(b.getStatus())||tenants.findByFrontendHost(host).isPresent()||tenants.findByEntryHost(host).isPresent())throw new IllegalArgumentException("仅能审查释放未被使用的退役域名");
  b.setStatus("RELEASED");bindings.saveAndFlush(b);audit.record(actor,id,null,"DOMAIN_REUSE_RELEASE",host,"SUCCESS","role="+role+";explicit reuse review;history retained",reason);return view(b);
 }
 /** Public fixed-purpose nonce reply, never tenant business, redirect or identity. */
 @Transactional(readOnly=true) public Map<String,Object> routing(String host,String challenge){
  if(challenge==null||!challenge.matches("[0-9a-f]{32}"))throw new AccessDeniedException("挑战无效");TenantDomainBinding b=bindings.findById(host).orElseThrow(()->new AccessDeniedException("未知候选域名"));
  if(!CANDIDATES.contains(b.getStatus())||b.getExpiresAt()==null||!Instant.now().isBefore(b.getExpiresAt())||!challenge.equals(b.getChallenge()))throw new AccessDeniedException("候选挑战已失效");
  Map<String,Object> out=new LinkedHashMap<>();out.put("tenantId",b.getTenantId());out.put("challenge",challenge);out.put("role",b.getRole());return out;
 }
}
