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
/** Candidate reservations never change the active host. The network probe runs without a DB lock. */
@Service @RequiredArgsConstructor
public class TenantDomainVerification {
 private final TenantRepository tenants;private final ControlAuditService audit;
 private final TenantDomainBindingRepository bindings;private final TenantDomainHistoryRepository history;
 private final TenantHostService hosts;private final OutboundEndpointPolicy outbound;private final PlatformTransactionManager manager;
 private long actor(){
  org.springframework.security.core.Authentication a=SecurityContextHolder.getContext().getAuthentication();ControlIdentity i=ControlIdentity.current();
  if(a==null||!a.isAuthenticated()||a.getAuthorities().stream().noneMatch(r->"ROLE_CONTROL".equals(r.getAuthority()))||i==null||i.getActorId()==null||i.getActorId()<=0||i.getTenantId()!=null||i.getAccessSessionId()!=null||TenantContext.currentTenantId()!=null)throw new AccessDeniedException("需要独立总控身份");return i.getActorId();
 }
 private Map<String,Object> view(TenantDomainBinding b){Map<String,Object> v=new LinkedHashMap<>();v.put("hostname",b.getHostname());v.put("tenantId",b.getTenantId());v.put("status",b.getStatus());v.put("version",b.getVersion());v.put("expiresAt",b.getExpiresAt());v.put("verifiedAt",b.getVerifiedAt());return v;}
 @Transactional(readOnly=true) public List<Map<String,Object>> candidates(Long id){actor();tenants.findById(id).orElseThrow(ControlService::invalid);List<Map<String,Object>> r=new ArrayList<>();for(TenantDomainBinding b:bindings.findByTenantIdAndStatusIn(id,Arrays.asList("PENDING","VERIFIED")))r.add(view(b));return r;}
 @Transactional public Map<String,Object> prepare(Long id,String raw,String reason){
  long actor=actor();TenantManagementService.reason(reason);Tenant t=tenants.lock(id).orElseThrow(ControlService::invalid);String host=hosts.validateAssignment(raw);
  Tenant taken=tenants.findByFrontendHost(host).orElse(null);if(taken!=null&&!id.equals(taken.getId()))throw new IllegalArgumentException("域名不可用");
  TenantDomainBinding b=bindings.lock(host).orElse(null);
  if(b==null&&history.findByHostname(host).isPresent())throw new IllegalArgumentException("域名已退役，须先审查释放");
  if(b!=null&&!(id.equals(b.getTenantId())&&!"RETIRED".equals(b.getStatus())||"RELEASED".equals(b.getStatus())))throw new IllegalArgumentException("域名被占用或已退役");
  if(host.equals(t.getFrontendHost())&&t.isDomainVerified())throw new IllegalArgumentException("该域名已生效，无需重新激活");
  for(TenantDomainBinding old:bindings.findByTenantIdAndStatusIn(id,Arrays.asList("PENDING","VERIFIED"))){if(!host.equals(old.getHostname())){old.setStatus("RETIRED");old.setChallenge(null);old.setExpiresAt(null);bindings.save(old);}}
  if(b==null){b=new TenantDomainBinding();b.setHostname(host);}b.setTenantId(id);b.setStatus("PENDING");b.setChallenge(UUID.randomUUID().toString().replace("-",""));b.setExpiresAt(Instant.now().plusSeconds(900));b.setVerifiedAt(null);bindings.saveAndFlush(b);
  audit.record(actor,id,null,"DOMAIN_PREPARE",host,"SUCCESS","active host unchanged;ttl=900s",reason);return view(b);
 }
 private void candidate(TenantDomainBinding b,Long id,long version,String state){if(!id.equals(b.getTenantId())||b.getVersion()!=version||!state.equals(b.getStatus())||b.getExpiresAt()==null||!Instant.now().isBefore(b.getExpiresAt()))throw new IllegalArgumentException("候选域名已变更或验证已过期，请重新准备");}
 public Map<String,Object> verify(Long id,String raw,long version){
  long actor=actor();String host=hosts.validateAssignment(raw);TenantDomainBinding snapshot=bindings.findById(host).orElseThrow(()->new IllegalArgumentException("须先准备候选域名"));candidate(snapshot,id,version,"PENDING");String challenge=snapshot.getChallenge();
  try{JsonNode reply=new ObjectMapper().readTree(outbound.routingCheck(host,challenge));if(!reply.path("tenantId").isIntegralNumber()||reply.path("tenantId").asLong()!=id||!challenge.equals(reply.path("challenge").asText()))throw new IllegalArgumentException("路由挑战不匹配");}
  catch(Exception failure){audit.failure(actor,id,null,"DOMAIN_VERIFY",host);throw new IllegalArgumentException("HTTPS证书、挑战或租户路由核验失败；当前域名未变更");}
  return new TransactionTemplate(manager).execute(tx->{tenants.lock(id).orElseThrow(ControlService::invalid);TenantDomainBinding b=bindings.lock(host).orElseThrow(ControlService::invalid);candidate(b,id,version,"PENDING");if(!challenge.equals(b.getChallenge()))throw new IllegalArgumentException("挑战已变更");b.setStatus("VERIFIED");b.setVerifiedAt(Instant.now());bindings.saveAndFlush(b);audit.record(actor,id,null,"DOMAIN_CANDIDATE_VERIFIED",host,"SUCCESS","TLS hostname and nonce-bound tenant route checked",null);return view(b);});
 }
 @Transactional public Map<String,Object> activate(Long id,String raw,long version,String reason){
  long actor=actor();TenantManagementService.reason(reason);Tenant t=tenants.lock(id).orElseThrow(ControlService::invalid);String host=hosts.validateAssignment(raw);TenantDomainBinding b=bindings.lock(host).orElseThrow(ControlService::invalid);
  if(id.equals(b.getTenantId())&&"ACTIVE".equals(b.getStatus())&&host.equals(t.getFrontendHost())&&t.isDomainVerified())return view(b);
  candidate(b,id,version,"VERIFIED");String previous=t.getFrontendHost();
  if(previous!=null&&!previous.equals(host)){
   TenantDomainBinding old=bindings.lock(previous).orElseThrow(()->new IllegalArgumentException("原域名登记不完整，禁止切换"));old.setStatus("RETIRED");old.setChallenge(null);old.setExpiresAt(null);bindings.save(old);
   if(!history.findByHostname(previous).isPresent()){TenantDomainHistory retired=new TenantDomainHistory();retired.setTenantId(id);retired.setHostname(previous);history.saveAndFlush(retired);}
  }
  b.setStatus("ACTIVE");b.setChallenge(null);b.setExpiresAt(null);bindings.saveAndFlush(b);t.setFrontendHost(host);t.setDomainVerified(true);t.setPolicyVersion(t.getPolicyVersion()+1);tenants.saveAndFlush(t);
  audit.record(actor,id,null,"DOMAIN_ACTIVATE",host,"SUCCESS","previous="+Objects.toString(previous,"")+";policyVersion="+t.getPolicyVersion(),reason);return view(b);
 }
 @Transactional public Map<String,Object> release(Long id,String raw,long version,String reason){
  long actor=actor();TenantManagementService.reason(reason);tenants.lock(id).orElseThrow(ControlService::invalid);String host=hosts.validateAssignment(raw);TenantDomainBinding b=bindings.lock(host).orElseThrow(ControlService::invalid);
  if(!id.equals(b.getTenantId())||version!=b.getVersion()||!"RETIRED".equals(b.getStatus())||tenants.findByFrontendHost(host).isPresent())throw new IllegalArgumentException("仅能审查释放未被使用的退役域名");
  b.setStatus("RELEASED");bindings.saveAndFlush(b);audit.record(actor,id,null,"DOMAIN_REUSE_RELEASE",host,"SUCCESS","explicit retired-host reuse review;history retained",reason);return view(b);
 }
 /** Public fixed-purpose nonce reply, not a route into tenant business or identity. */
 @Transactional(readOnly=true) public Map<String,Object> routing(String host,String challenge){
  if(challenge==null||!challenge.matches("[0-9a-f]{32}"))throw new AccessDeniedException("挑战无效");
  TenantDomainBinding b=bindings.findById(host).orElseThrow(()->new AccessDeniedException("未知候选域名"));
  if(!Arrays.asList("PENDING","VERIFIED").contains(b.getStatus())||b.getExpiresAt()==null||!Instant.now().isBefore(b.getExpiresAt())||!challenge.equals(b.getChallenge()))throw new AccessDeniedException("候选挑战已失效");
  Map<String,Object> out=new LinkedHashMap<>();out.put("tenantId",b.getTenantId());out.put("challenge",challenge);return out;
 }
}
