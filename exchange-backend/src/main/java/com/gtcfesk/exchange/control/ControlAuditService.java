package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.*;
import javax.servlet.http.HttpServletRequest;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class ControlAuditService {
 private final ControlAuditLogRepository logs;
 private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
 @Transactional
 public void record(Long actor,Long tenant,String session,String action,String object,String outcome,String detail,String reason){
  ControlAuditLog log=new ControlAuditLog();log.setActorId(actor);log.setTenantId(tenant);log.setAccessSessionId(session);
  log.setRequestId(UUID.randomUUID().toString());log.setAction(cut(action,128));log.setObjectRef(cut(object,255));log.setOutcome(cut(outcome,32));
  log.setDetail(safeDetail(detail));log.setReason(cut(reason,512));
  RequestAttributes attributes=RequestContextHolder.getRequestAttributes();
  if(attributes instanceof ServletRequestAttributes){HttpServletRequest request=((ServletRequestAttributes)attributes).getRequest();log.setRemoteAddress(cut(request.getRemoteAddr(),64));}
  logs.save(log);
 }
 @Transactional
 public void recordCurrent(String action,String object,String detail,String reason){
  ControlIdentity i=ControlIdentity.current();if(i!=null)record(i.getActorId(),i.getTenantId(),i.getAccessSessionId(),action,object,"SUCCESS",detail,reason);
 }
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
 public void failure(Long actor,Long tenant,String session,String action,String object){record(actor,tenant,session,action,object,"FAILED","",null);}
 private static String safeDetail(String detail){
  if(detail==null||detail.isEmpty())return "{}";
  try{com.fasterxml.jackson.databind.JsonNode node=JSON.readTree(detail);if(node!=null&&(node.isObject()||node.isArray()))return com.gtcfesk.exchange.common.LogRedaction.sanitize(detail);}catch(java.io.IOException ignored){}
  try{return com.gtcfesk.exchange.common.LogRedaction.sanitize(JSON.writeValueAsString(java.util.Collections.singletonMap("summary",cut(detail,4000))));}catch(java.io.IOException e){throw new IllegalStateException("Audit serialization failed",e);}
 }
 private static String cut(String text,int max){return text==null?null:text.substring(0,Math.min(max,text.length()));}
}
