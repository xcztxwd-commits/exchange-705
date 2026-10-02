package com.gtcfesk.exchange.control;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.nio.file.*;

/** Operational metadata only: reuse immutable audit records; never store tokens, SQL values, identity or chat text. */
@Service @RequiredArgsConstructor
public class OperationalIssueService {
 private final JdbcTemplate jdbc;private final ControlAuditService audit;private final PlatformTransactionManager manager;private final ObjectMapper json;
 @org.springframework.beans.factory.annotation.Value("${control.operations.failure-directory:}") private String failureDirectory;
 private volatile String delivery="DATABASE",deliveryFailure="";
 public void failed(Long tenant,String job,RuntimeException failure){
  if(tenant==null||tenant<=0||job==null||!job.matches("[a-z0-9-]{1,64}")||failure==null)throw new IllegalArgumentException("任务归属或名称无效");
  Map<String,Object> detail=new LinkedHashMap<>();detail.put("runId",UUID.randomUUID().toString());detail.put("job",job);detail.put("failureType",failure.getClass().getSimpleName());
  try{TransactionTemplate tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.execute(status->{audit.record(null,tenant,null,"TENANT_JOB_FAILED",job,"FAILED",encode(detail),"SYSTEM scheduled job; original idempotent engine retries on next tick");return null;});
   // Local-journal alerts stay sticky until a separately verified import; a later successful insert cannot hide lost delivery.
  }
  catch(RuntimeException storeFailure){delivery="NO_DURABLE_JOURNAL";deliveryFailure=storeFailure.getClass().getSimpleName();detail.put("tenantId",tenant);detail.put("occurredAt",java.time.Instant.now().toString());detail.put("deliveryFailureType",deliveryFailure);
   try{if(failureDirectory==null||failureDirectory.codePoints().allMatch(Character::isWhitespace))throw new IllegalStateException("Restricted failure directory not configured");Path directory=Paths.get(failureDirectory).toAbsolutePath().normalize();if(!Files.isDirectory(directory)||Files.isSymbolicLink(directory))throw new IllegalStateException("Precreated restricted directory required");try(java.util.stream.Stream<Path> files=Files.list(directory)){if(files.limit(1001).count()>=1000)throw new IllegalStateException("Failure journal capacity reached");}Path file=directory.resolve(detail.get("runId")+".json"),temporary=directory.resolve(detail.get("runId")+".tmp");byte[] bytes=encode(detail).getBytes(java.nio.charset.StandardCharsets.UTF_8);try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW)){java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE);delivery="LOCAL_JOURNAL_REQUIRES_IMPORT";}catch(Exception journalFailure){org.slf4j.LoggerFactory.getLogger(getClass()).error("Operational failure persistence unavailable: tenant={}, job={}, type={}",tenant,job,journalFailure.getClass().getSimpleName());}
  }
 }
 public Long actor(){ControlIdentity i=ControlIdentity.current();org.springframework.security.core.Authentication a=SecurityContextHolder.getContext().getAuthentication();if(i==null||i.getAccessSessionId()!=null||i.getActorId()==null||i.getActorId()<=0||a==null||!a.isAuthenticated()||a.getAuthorities().stream().noneMatch(x->"ROLE_CONTROL".equals(x.getAuthority())))throw new AccessDeniedException("需要独立总控身份");return i.getActorId();}
 public Map<String,Object> health(){actor();return com.gtcfesk.exchange.common.ImmutableMaps.of("failureDelivery",delivery,"failureDeliveryType",deliveryFailure,"retryPolicy","original-engine-next-tick","directBalanceRepair",false);}
 public List<Map<String,Object>> list(Long tenant,int page){actor();if(tenant==null||tenant<=0||page<0||page>100000)throw new IllegalArgumentException("需要明确租户和有效页码");
  List<Map<String,Object>> rows=jdbc.queryForList("SELECT f.object_ref AS job,MIN(f.created_at) AS first_seen,MAX(f.created_at) AS last_seen,COUNT(*) AS attempts,MAX(f.id) AS latest_failure_id,COALESCE((SELECT MAX(r.id) FROM control_audit_log r WHERE r.tenant_id=f.tenant_id AND r.action='OPERATIONAL_ISSUE_REVIEW' AND r.object_ref=f.object_ref),0) AS last_review_id FROM control_audit_log f WHERE f.tenant_id=? AND f.action IN ('TENANT_JOB_FAILED','CHAT_RETENTION_SKIPPED') GROUP BY f.tenant_id,f.object_ref ORDER BY latest_failure_id DESC LIMIT 50 OFFSET ?",tenant,page*50);
  List<Map<String,Object>> normalized=new ArrayList<>();for(Map<String,Object> row:rows){Map<String,Object> lower=new LinkedHashMap<>();row.forEach((key,value)->lower.put(key.toLowerCase(Locale.ROOT),value));normalized.add(lower);}return normalized;
 }
 @org.springframework.transaction.annotation.Transactional public void review(Long tenant,String job,long expectedFailure,String reason,String reportHash,String result){Long actor=actor();TenantManagementService.reason(reason);if(tenant==null||tenant<=0||job==null||!job.matches("[a-z0-9-]{1,64}")||expectedFailure<=0||reportHash==null||!reportHash.matches("[a-f0-9]{64}")||!Arrays.asList("RECOVERED","PRESERVED","EXTERNAL_BLOCKED").contains(result))throw new IllegalArgumentException("需要最新失败、处理结果和复核证据SHA256");
  Long latest=jdbc.queryForObject("SELECT MAX(id) FROM control_audit_log WHERE tenant_id=? AND object_ref=? AND action IN ('TENANT_JOB_FAILED','CHAT_RETENTION_SKIPPED')",Long.class,tenant,job);if(latest==null||latest!=expectedFailure)throw new IllegalArgumentException("异常已变化，请重新读取和复核");audit.record(actor,tenant,null,"OPERATIONAL_ISSUE_REVIEW",job,result,encode(com.gtcfesk.exchange.common.ImmutableMaps.of("expectedFailureId",expectedFailure,"evidenceSha256",reportHash,"result",result)),reason);
 }
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalStateException("运行证据序列化失败",e);}}
}
