package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.activity.TrialFunds;
import com.gtcfesk.exchange.admin.dto.UpdateTrialAccountRequest;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.control.ControlIdentity;
import com.gtcfesk.exchange.entity.BalanceAdjustment;
import com.gtcfesk.exchange.repository.BalanceAdjustmentRepository;
import com.gtcfesk.exchange.support.SupportService;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Trial principal, durable receipts and audit commit atomically under the existing money locks. */
@Service @RequiredArgsConstructor
public class AdminTrialAccountService {
 private final TrialFunds funds;
 private final BalanceAdjustmentRepository receipts;
 private final ControlAuditService audit;
 private static final ObjectMapper JSON=new ObjectMapper().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII,true);

 private Authentication administrator(){
  Authentication auth=SecurityContextHolder.getContext().getAuthentication();
  if(auth==null||!auth.isAuthenticated()||auth.getAuthorities().stream().noneMatch(a->"ROLE_SUPER_ADMIN".equals(a.getAuthority())))throw new AccessDeniedException("无权管理体验金账户");
  return auth;
 }
 @Transactional public Map<String,Object> account(Long user){administrator();return funds.adminAccount(user);}

 @Transactional public Map<String,Object> update(Long user,UpdateTrialAccountRequest input){
  Authentication auth=administrator();
  if(user==null||input==null||!input.isConfirm()||input.getEnabled()==null||input.getExpectedVersion()==null||input.getExpectedVersion()<0||input.getRequestId()==null||!input.getRequestId().matches("trial-[A-Za-z0-9_-]{16,58}"))throw new IllegalArgumentException("请确认调整并提交有效请求编号及账户版本");
  if(input.getAvailable()==null||input.getAvailable().signum()<0||input.getAvailable().scale()>16||input.getAvailable().precision()-input.getAvailable().scale()>16)throw new IllegalArgumentException("体验金余额必须为非负数，最多16位整数及16位小数");
  String reason=input.getReason()==null?"":input.getReason().trim();if(reason.length()>500)throw new IllegalArgumentException("修改原因不能超过500字");
  Long tenant=TenantContext.requireTenantId();String actorType=ControlIdentity.isAccess()?"CONTROL":"ADMIN";
  Long actor=ControlIdentity.isAccess()?ControlIdentity.actorId():Long.valueOf(auth.getName());
  String canonical=Arrays.asList("TRIAL_ACCOUNT",user,input.getEnabled(),input.getAvailable().stripTrailingZeros().toPlainString(),input.getExpectedVersion(),reason,actorType,actor).toString();
  String hash=SupportService.sha256(canonical.getBytes(StandardCharsets.UTF_8));
  funds.lockForQuote(user);
  BalanceAdjustment previous=receipts.findByTenantIdAndRequestKey(tenant,input.getRequestId()).orElse(null);
  if(previous!=null){
   if(!Objects.equals(previous.getUserId(),user)||!hash.equals(previous.getRequestHash()))throw new IllegalArgumentException("请求编号已用于其他调整");
   Map<String,Object> out=funds.adminAccount(user);out.put("success",true);out.put("requestId",input.getRequestId());out.put("replayed",true);return out;
  }
  Map<String,Object> before=funds.adminAccount(user);
  Map<String,Object> after=funds.adjustForAdmin(user,input.getExpectedVersion(),input.getAvailable(),input.getEnabled(),input.getRequestId());
  Map<String,Object> changes=new LinkedHashMap<>();changes.put("account","TRIAL");changes.put("userId",user);changes.put("beforeAvailable",before.get("available"));changes.put("afterAvailable",after.get("available"));changes.put("beforeEnabled",before.get("enabled"));changes.put("afterEnabled",after.get("enabled"));changes.put("frozen",after.get("frozen"));changes.put("requestId",input.getRequestId());
  String detail;try{detail=JSON.writeValueAsString(changes);}catch(java.io.IOException e){throw new IllegalStateException("调整记录生成失败",e);}
  BalanceAdjustment receipt=new BalanceAdjustment();receipt.setUserId(user);receipt.setRequestKey(input.getRequestId());receipt.setRequestHash(hash);receipt.setActorType(actorType);receipt.setActorId(actor);receipt.setReason(reason);receipt.setChanges(detail);receipts.saveAndFlush(receipt);
  audit.record(actor,tenant,ControlIdentity.current()==null?null:ControlIdentity.current().getAccessSessionId(),"TRIAL_ACCOUNT_UPDATE","user:"+user,"SUCCESS",detail,reason);
  after.put("success",true);after.put("requestId",input.getRequestId());after.put("replayed",false);return after;
 }
}
