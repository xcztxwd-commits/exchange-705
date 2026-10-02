package com.gtcfesk.exchange.control;
import com.gtcfesk.exchange.common.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.*;
@Service
public class ControlService {
 private final ControlAdminRepository admins;private final ControlAccessSessionRepository sessions;private final TenantRepository tenants;
 private final ControlAuditService audit;private final JwtUtil jwt;private final PasswordEncoder passwords;private final ControlMfa mfa;private final TenantHostService hosts;
 private final boolean requireMfa;
 public ControlService(ControlAdminRepository admins,ControlAccessSessionRepository sessions,TenantRepository tenants,ControlAuditService audit,JwtUtil jwt,PasswordEncoder passwords,ControlMfa mfa,TenantHostService hosts,@Value("${platform.control-require-mfa:true}") boolean requireMfa){this.admins=admins;this.sessions=sessions;this.tenants=tenants;this.audit=audit;this.jwt=jwt;this.passwords=passwords;this.mfa=mfa;this.hosts=hosts;this.requireMfa=requireMfa;}
 @Transactional public Map<String,Object> login(String account,String password,String code){
  ControlAdmin a=admins.findByAccount(BackendLoginRegistry.normalize(account)).orElseThrow(()->new AccessDeniedException("账号或验证信息错误"));
  if(!a.isEnabled()||password==null||!passwords.matches(password,a.getPasswordHash())||(requireMfa&&!a.isMfaEnabled())||(a.isMfaEnabled()&&!mfa.verify(a.getMfaSecret(),code)))throw new AccessDeniedException("账号或验证信息错误");
  Map<String,Object> claims=new LinkedHashMap<>();claims.put("userType","control");claims.put("actorVersion",a.getSessionVersion());
  Map<String,Object> result=new LinkedHashMap<>();result.put("token",jwt.generateToken("control-"+a.getId(),claims,3600));result.put("expiresAt",System.currentTimeMillis()+3600000);result.put("user",a);
  audit.record(a.getId(),null,null,"CONTROL_LOGIN",String.valueOf(a.getId()),"SUCCESS","",null);return result;
 }
 public ControlAdmin validateControl(Claims claims){
  if(!"control".equals(claims.get("userType"))||claims.getSubject()==null||!claims.getSubject().matches("control-[1-9][0-9]*"))throw invalid();
  ControlAdmin a=admins.findById(Long.valueOf(claims.getSubject().substring(8))).orElseThrow(ControlService::invalid);
  if(!a.isEnabled()||!(claims.get("actorVersion") instanceof Number)||a.getSessionVersion()!=((Number)claims.get("actorVersion")).longValue()||(requireMfa&&!a.isMfaEnabled()))throw invalid();return a;
 }
 @Transactional public void logout(){ControlAdmin a=admins.lock(ControlIdentity.actorId()).orElseThrow(ControlService::invalid);a.setSessionVersion(a.getSessionVersion()+1);audit.record(a.getId(),null,null,"CONTROL_LOGOUT",String.valueOf(a.getId()),"SUCCESS","",null);}
 @Transactional public Map<String,Object> ticket(Long tenantId,String binding){
  validateBinding(binding);Long actor=ControlIdentity.actorId();ControlAdmin a=admins.findById(actor).orElseThrow(ControlService::invalid);
  tenants.findById(tenantId).orElseThrow(ControlService::invalid);String ticket=random();LocalDateTime now=LocalDateTime.now();
  ControlAccessSession s=new ControlAccessSession();s.setId(UUID.randomUUID().toString());s.setActorId(actor);s.setTenantId(tenantId);s.setActorVersion(a.getSessionVersion());s.setTicketHash(hash(ticket));s.setBrowserBindingHash(hash(binding));s.setTicketExpiresAt(now.plusSeconds(60));s.setExpiresAt(now.plusMinutes(60));s.setLastActivityAt(now);sessions.saveAndFlush(s);
  audit.record(actor,tenantId,s.getId(),"ACCESS_TICKET",String.valueOf(tenantId),"SUCCESS","",null);
  Map<String,Object> out=new LinkedHashMap<>();out.put("ticket",ticket);out.put("tenantId",tenantId);out.put("expiresAt",epoch(s.getTicketExpiresAt()));out.put("adminOrigin",hosts.adminOrigin());return out;
 }
 @Transactional public Map<String,Object> exchange(String ticket,String binding,Long expectedTenant){
  validateBinding(binding);if(ticket==null||!ticket.matches("[A-Za-z0-9_-]{43}"))throw invalid();
  ControlAccessSession s=sessions.lockTicket(hash(ticket)).orElseThrow(ControlService::invalid);LocalDateTime now=LocalDateTime.now();
  if(s.isConsumed()||s.isRevoked()||!s.getTicketExpiresAt().isAfter(now)||!s.getTenantId().equals(expectedTenant)||!MessageDigest.isEqual(hash(binding).getBytes(StandardCharsets.US_ASCII),s.getBrowserBindingHash().getBytes(StandardCharsets.US_ASCII)))throw invalid();
  ControlAdmin a=activeActor(s);Tenant t=tenants.findById(s.getTenantId()).orElseThrow(ControlService::invalid);s.setConsumed(true);s.setLastActivityAt(now);sessions.saveAndFlush(s);
  Map<String,Object> claims=new LinkedHashMap<>();claims.put("userType","control_access");claims.put("tenantId",s.getTenantId());claims.put("accessSessionId",s.getId());claims.put("actorVersion",a.getSessionVersion());
  Map<String,Object> user=new LinkedHashMap<>();user.put("id",-a.getId());user.put("controlActorId",a.getId());user.put("account",a.getAccount());user.put("userType","control");user.put("role","super_admin");user.put("isSuperAdmin",true);user.put("tenantId",t.getId());user.put("tenantName",t.getName());user.put("displayName","总控管理");
  Map<String,Object> session=new LinkedHashMap<>();session.put("id",s.getId());session.put("tenantId",t.getId());session.put("tenantName",t.getName());session.put("expiresAt",epoch(s.getExpiresAt()));
  Map<String,Object> result=new LinkedHashMap<>();result.put("token",jwt.generateToken("control_access-"+a.getId(),claims,Math.max(1,Duration.between(now,s.getExpiresAt()).getSeconds())));result.put("expiresAt",epoch(s.getExpiresAt()));result.put("user",user);result.put("accessSession",session);
  audit.record(a.getId(),t.getId(),s.getId(),"ACCESS_ENTER",String.valueOf(t.getId()),"SUCCESS","",null);return result;
 }
 @Transactional public ControlIdentity validateAccess(Claims claims){
  String id=claims.get("accessSessionId",String.class);if(id==null)throw invalid();ControlAccessSession s=sessions.lock(id).orElseThrow(ControlService::invalid);LocalDateTime now=LocalDateTime.now();
  if(!s.isConsumed()||s.isRevoked()||!s.getExpiresAt().isAfter(now)||!s.getLastActivityAt().plusMinutes(15).isAfter(now)||!claims.getSubject().equals("control_access-"+s.getActorId())||!(claims.get("tenantId") instanceof Number)||((Number)claims.get("tenantId")).longValue()!=s.getTenantId())throw invalid();
  activeActor(s);return new ControlIdentity(s.getActorId(),s.getTenantId(),s.getId());
 }
 @Transactional public void activity(){ControlIdentity i=ControlIdentity.current();if(i==null||i.getAccessSessionId()==null)throw invalid();ControlAccessSession s=sessions.lock(i.getAccessSessionId()).orElseThrow(ControlService::invalid);LocalDateTime now=LocalDateTime.now();if(s.isRevoked()||!s.getExpiresAt().isAfter(now)||!s.getLastActivityAt().plusMinutes(15).isAfter(now))throw invalid();activeActor(s);s.setLastActivityAt(now);}
 private ControlAdmin activeActor(ControlAccessSession s){ControlAdmin a=admins.findById(s.getActorId()).orElseThrow(ControlService::invalid);if(!a.isEnabled()||a.getSessionVersion()!=s.getActorVersion()||(requireMfa&&!a.isMfaEnabled()))throw invalid();return a;}
 @Transactional public void revoke(String id){ControlAccessSession s=sessions.lock(id).orElseThrow(ControlService::invalid);if(!s.getActorId().equals(ControlIdentity.actorId()))throw invalid();s.setRevoked(true);audit.record(s.getActorId(),s.getTenantId(),s.getId(),"ACCESS_REVOKE",id,"SUCCESS","",null);}
 static String hash(String text){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
 static void validateBinding(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{32,128}"))throw invalid();}
 static long epoch(LocalDateTime value){return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();}
 static AccessDeniedException invalid(){return new AccessDeniedException("访问会话无效或已失效");}
}
