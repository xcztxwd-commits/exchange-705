package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.entity.OperationLog;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Local test sink only. No real provider, network send, login/registration side effect, or code in HTTP/audit. */
@Service @RequiredArgsConstructor
public class TenantSmsService {
 private final SystemConfigService configs;private final RegistrationSecurity limits;private final StringRedisTemplate redis;
 private final AdminUserRepository admins;private final AdminPermissionService permissions;private final OperationLogRepository logs;
 private final ControlAuditService audit;private final OperationalIssueService issues;private final PlatformTransactionManager manager;private final ObjectMapper json;
 @org.springframework.beans.factory.annotation.Value("${tenant.sms.local-sink-enabled:false}") private boolean enabled;
 @org.springframework.beans.factory.annotation.Value("${tenant.sms.sink-directory:}") private String directory;
 @org.springframework.beans.factory.annotation.Value("${tenant.secrets.key:}") private String encryptionKey;
 private static final DefaultRedisScript<Long> ACTIVATE=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] then redis.call('SET',KEYS[1],ARGV[2],'KEEPTTL'); return 1 end; return 0",Long.class);
 private static final DefaultRedisScript<Long> CONSUME=new DefaultRedisScript<>("local v=redis.call('GET',KEYS[1]); if not v or string.sub(v,1,2)~='A|' then return 0 end; if v~=ARGV[1] then return 0 end; redis.call('DEL',KEYS[1]);return 1",Long.class);
 private static class Operator {Long id;String type;AdminUser admin;ControlIdentity control;}
 private Operator operator(boolean write){
  Long tenant=TenantContext.requireTenantId();org.springframework.security.core.Authentication a=SecurityContextHolder.getContext().getAuthentication();
  if(a==null||!a.isAuthenticated()||a.getAuthorities().stream().noneMatch(x->Arrays.asList("ROLE_ADMIN","ROLE_SUPER_ADMIN").contains(x.getAuthority()))||a.getAuthorities().stream().anyMatch(x->"ROLE_AGENT".equals(x.getAuthority())))throw new AccessDeniedException("短信配置测试需要真实管理员或目标租户总控访问身份");
  permissions.require("settings",write?"save":"");Operator o=new Operator();o.control=ControlIdentity.current();
  if(o.control!=null){if(o.control.getAccessSessionId()==null||o.control.getActorId()==null||o.control.getActorId()<=0||!tenant.equals(o.control.getTenantId())||a.getAuthorities().stream().noneMatch(x->"ROLE_CONTROL_ACCESS".equals(x.getAuthority())))throw new AccessDeniedException("目标租户总控访问身份无效");o.id=o.control.getActorId();o.type="CONTROL";}
  else{try{o.id=Long.valueOf(a.getName());}catch(NumberFormatException e){throw new AccessDeniedException("操作者无效");}o.admin=admins.findByTenantIdAndId(tenant,o.id).filter(x->Boolean.TRUE.equals(x.getEnabled())).orElseThrow(()->new AccessDeniedException("操作者已停用"));o.type="ADMIN";}return o;
 }
 public Map<String,Object> status(){operator(false);boolean local=enabled&&"local-sink".equals(configs.getConfigValue("sms.provider"));return com.gtcfesk.exchange.common.ImmutableMaps.of("status",local?"LOCAL_SINK_ONLY":"BLOCKED","localSinkEnabled",local,"realSupplierVerified",false,"purposes",Collections.singletonList("CONFIG_TEST"),"expiresIn",180,"note","真实供应商未指定；不发送真实短信，不用于注册或登录");}
 private void input(String recipient,String purpose){if(!"CONFIG_TEST".equals(purpose))throw new IllegalArgumentException("短信用途仅允许 CONFIG_TEST；不能引入登录或注册流程");if(recipient==null||!recipient.matches("\\+120255501[0-9]{2}"))throw new IllegalArgumentException("本地 sink 仅接收 +12025550100 至 +12025550199 合成号码");if(!enabled||!"local-sink".equals(configs.getConfigValue("sms.provider")))throw new SecurityFailure(503,"SMS_PROVIDER_BLOCKED",0);}
 private static String digest(String value){return com.gtcfesk.exchange.support.SupportService.sha256(value.getBytes(StandardCharsets.UTF_8));}
 private String key(String id,String purpose){return "security:{sms}:tenant:"+TenantContext.requireTenantId()+":"+purpose+":"+id;}
 private String proof(Operator operator,String id,String recipient,String purpose,String code){try{byte[] key=Base64.getDecoder().decode(encryptionKey);if(key.length!=32)throw new IllegalArgumentException();javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(key,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(json.writeValueAsBytes(Arrays.asList(TenantContext.requireTenantId(),operator.type,operator.id,id,recipient,purpose,code))));}catch(Exception e){throw new IllegalStateException("短信证明密钥不可用");}}
 private Path sink(String id)throws java.io.IOException{
  if(directory==null||directory.codePoints().allMatch(Character::isWhitespace))throw new IllegalStateException("未配置受限本地短信 sink");Path base=Paths.get(directory).toAbsolutePath().normalize();
  if(!Files.isDirectory(base,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(base))throw new IllegalStateException("短信 sink 必须由运维预先创建并限制 ACL");
  Path target=base.resolve("tenant-"+TenantContext.requireTenantId());Files.createDirectories(target);if(Files.isSymbolicLink(target)||!target.toRealPath().startsWith(base.toRealPath()))throw new IllegalStateException("短信 sink 边界无效");
  try(java.util.stream.Stream<Path> files=Files.list(target)){if(files.limit(1001).count()>=1000)throw new IllegalStateException("本地短信 sink 容量已满");}return target.resolve(id+".json");
 }
 private void record(Operator o,String action,String id,String recipientDigest,String purpose){TransactionTemplate tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.execute(s->{
  String detail;try{detail=json.writeValueAsString(com.gtcfesk.exchange.common.ImmutableMaps.of("requestId",id,"recipientSha256",recipientDigest,"purpose",purpose,"provider","LOCAL_SINK_ONLY"));}catch(java.io.IOException e){throw new IllegalStateException("短信审计序列化失败");}
  if(o.control!=null)audit.record(o.id,TenantContext.requireTenantId(),o.control.getAccessSessionId(),action,id,"SUCCESS",detail,"Authenticated operator CONFIG_TEST; no real SMS delivery");
  else{OperationLog log=new OperationLog();log.setAdminId(o.id);log.setOperationType("短信配置测试");log.setOperationAction(action);log.setTargetType("sms-test");log.setTargetInfo("recipientSha256="+recipientDigest);log.setRequestMethod("POST");log.setRequestUrl("/api/admin/config/sms/"+("SMS_LOCAL_CONSUMED".equals(action)?"consume":"test"));log.setRequestParams(detail);logs.saveAndFlush(log);}return null;});
 }
 public Map<String,Object> send(String recipient,String purpose){Operator o=operator(true);input(recipient,purpose);String recipientDigest=digest(recipient);limits.limitSms(recipientDigest);String id=UUID.randomUUID().toString().replace("-","");String key=key(id,purpose),pending="P|"+id;Path file=null;
  try{String code=String.format(Locale.ROOT,"%06d",new java.security.SecureRandom().nextInt(1000000));String value="A|"+proof(o,id,recipient,purpose,code);
   if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key,pending,Duration.ofSeconds(180))))throw SecurityFailure.unavailable();file=sink(id);Path temporary=file.resolveSibling(id+".tmp");
   byte[] bytes=json.writeValueAsBytes(com.gtcfesk.exchange.common.ImmutableMaps.of("tenantId",TenantContext.requireTenantId(),"requestId",id,"recipient",recipient,"purpose",purpose,"code",code,"expiresAt",java.time.Instant.now().plusSeconds(180).toString(),"provider","LOCAL_SINK_ONLY"));
   try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW)){java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE);
   // Code stays PENDING until actual-operator audit commits. A process failure cannot expose an unaudited usable code.
   record(o,"SMS_LOCAL_PREPARED",id,recipientDigest,purpose);
   if(!Long.valueOf(1).equals(redis.execute(ACTIVATE,Collections.singletonList(key),pending,value)))throw SecurityFailure.unavailable();
   return com.gtcfesk.exchange.common.ImmutableMaps.of("requestId",id,"expiresIn",180,"status","LOCAL_SINK_ONLY","realSupplierVerified",false);
  }catch(Exception e){try{redis.delete(key);}catch(Exception ignored){}try{if(file!=null){Files.deleteIfExists(file);Files.deleteIfExists(file.resolveSibling(id+".tmp"));}}catch(java.io.IOException ignored){}issues.failed(TenantContext.requireTenantId(),"sms-local-sink",new IllegalStateException("Local SMS operation unavailable"));throw SecurityFailure.unavailable();}
 }
 public Map<String,Object> consume(String id,String recipient,String purpose,String code){Operator o=operator(true);input(recipient,purpose);if(id==null||!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("短信请求编号无效");limits.limitSmsVerify(id);if(code==null||!code.matches("[0-9]{6}"))throw new SecurityFailure(400,"SMS_CODE_INVALID",0);
  try{Long consumed=redis.execute(CONSUME,Collections.singletonList(key(id,purpose)),"A|"+proof(o,id,recipient,purpose,code));if(!Long.valueOf(1).equals(consumed))throw new SecurityFailure(400,"SMS_CODE_INVALID",0);
   record(o,"SMS_LOCAL_CONSUMED",id,digest(recipient),purpose);return com.gtcfesk.exchange.common.ImmutableMaps.of("verified",true,"purpose","CONFIG_TEST","realSupplierVerified",false);
  }catch(SecurityFailure e){throw e;}catch(RuntimeException e){issues.failed(TenantContext.requireTenantId(),"sms-local-sink",new IllegalStateException("Local SMS verification unavailable"));throw SecurityFailure.unavailable();}
 }
}
