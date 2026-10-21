package com.gtcfesk.exchange.user;
import com.gtcfesk.exchange.admin.dto.UpdateUserBalanceRequest;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.support.SupportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.util.*;
@Service @RequiredArgsConstructor
public class BalanceAdjustmentService {
 private static final String CANCELLED="CANCELLED";
 private final UserAccountRepository users;private final AssetAccountRepository assets;
 private final BalanceAdjustmentRepository adjustments;private final ControlAuditService audit;
 @Transactional public void adjust(UpdateUserBalanceRequest input){
  Authentication auth=SecurityContextHolder.getContext().getAuthentication();
  if(auth==null||auth.getAuthorities().stream().noneMatch(a->"ROLE_SUPER_ADMIN".equals(a.getAuthority())))throw new org.springframework.security.access.AccessDeniedException("无权调整资金");
  if(!input.isConfirm()||input.getIdempotencyKey()==null||!input.getIdempotencyKey().matches("[A-Za-z0-9_-]{16,64}"))throw new IllegalArgumentException("必须确认调整并提交有效幂等键");
  if(input.getRemark()!=null&&input.getRemark().length()>500)throw new IllegalArgumentException("修改原因不能超过500字");
  Long tenant=TenantContext.requireTenantId();users.lockById(input.getUserId()).orElseThrow(()->new IllegalArgumentException("用户不存在"));
  String actorType=ControlIdentity.isAccess()?"CONTROL":"ADMIN";Long actor=ControlIdentity.isAccess()?ControlIdentity.actorId():Long.valueOf(auth.getName());
  String canonical=Arrays.asList(input.getUserId(),input.getFundBalance(),input.getContractBalance(),input.getOptionBalance(),input.getRemark(),actorType,actor).toString();
  String hash=SupportService.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  BalanceAdjustment previous=adjustments.findByTenantIdAndRequestKey(tenant,input.getIdempotencyKey()).orElse(null);
  if(previous!=null){if(!previous.getRequestHash().equals(hash)||!Objects.equals(previous.getReason(),input.getRemark()))throw new IllegalArgumentException("幂等键已用于不同调整");if(CANCELLED.equals(previous.getChanges()))throw new com.gtcfesk.exchange.common.BusinessException("原请求已取消，请重新编辑余额后保存");return;}
  Map<String,BigDecimal> targets=new LinkedHashMap<>();targets.put("FUND",input.getFundBalance());targets.put("CONTRACT",input.getContractBalance());targets.put("OPTION",input.getOptionBalance());
  Map<String,AssetAccount> accounts=new HashMap<>();for(AssetAccount account:assets.lockByUserId(input.getUserId()))accounts.put(account.getCoin(),account);
  List<String> changes=new ArrayList<>();
  for(Map.Entry<String,BigDecimal> entry:targets.entrySet()){
   BigDecimal after=entry.getValue();if(after==null)continue;
   if(after.signum()<0||after.scale()>16||after.precision()-after.scale()>16)throw new IllegalArgumentException("余额范围无效");
   AssetAccount account=accounts.get(entry.getKey());if(account==null){account=new AssetAccount();account.setUserId(input.getUserId());account.setCoin(entry.getKey());}
   BigDecimal before=account.getAvailable();changes.add(entry.getKey()+":"+before.toPlainString()+"->"+after.toPlainString()+";delta="+after.subtract(before).toPlainString());account.setAvailable(after);assets.save(account);
  }
  if(changes.isEmpty())throw new IllegalArgumentException("缺少调整余额");
  BalanceAdjustment record=new BalanceAdjustment();record.setUserId(input.getUserId());record.setRequestKey(input.getIdempotencyKey());record.setRequestHash(hash);record.setActorType(actorType);record.setActorId(actor);record.setReason(input.getRemark());record.setChanges(String.join(";",changes));adjustments.saveAndFlush(record);
  audit.recordCurrent("BALANCE_ADJUST",record.getId().toString(),record.getChanges(),input.getRemark());
 }
 /** Resolve a cached request without applying it; the receipt also blocks a delayed original request. */
 @Transactional public Map<String,Object> reconcile(UpdateUserBalanceRequest input){
  Authentication auth=SecurityContextHolder.getContext().getAuthentication();
  if(auth==null||!auth.isAuthenticated()||auth.getAuthorities().stream().noneMatch(a->"ROLE_SUPER_ADMIN".equals(a.getAuthority())))throw new org.springframework.security.access.AccessDeniedException("无权调整资金");
  if(input.getUserId()==null||!input.isConfirm()||input.getIdempotencyKey()==null||!input.getIdempotencyKey().matches("[A-Za-z0-9_-]{16,64}"))throw new IllegalArgumentException("待确认请求无效");
  if(input.getRemark()!=null&&input.getRemark().length()>500)throw new IllegalArgumentException("修改原因不能超过500字");
  Long tenant=TenantContext.requireTenantId();UserAccount user=users.lockById(input.getUserId()).orElseThrow(()->new IllegalArgumentException("用户不存在"));
  String actorType=ControlIdentity.isAccess()?"CONTROL":"ADMIN";Long actor=ControlIdentity.isAccess()?ControlIdentity.actorId():Long.valueOf(auth.getName());
  String canonical=Arrays.asList(input.getUserId(),input.getFundBalance(),input.getContractBalance(),input.getOptionBalance(),input.getRemark(),actorType,actor).toString();
  String hash=SupportService.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  BalanceAdjustment receipt=adjustments.findByTenantIdAndRequestKey(tenant,input.getIdempotencyKey()).orElse(null);
  if(receipt!=null){
   if(!receipt.getRequestHash().equals(hash)||!Objects.equals(receipt.getReason(),input.getRemark()))throw new IllegalArgumentException("幂等键已用于不同调整");
  }else{
   receipt=new BalanceAdjustment();receipt.setUserId(input.getUserId());receipt.setRequestKey(input.getIdempotencyKey());receipt.setRequestHash(hash);receipt.setActorType(actorType);receipt.setActorId(actor);receipt.setReason(input.getRemark());receipt.setChanges(CANCELLED);adjustments.saveAndFlush(receipt);
   audit.recordCurrent("BALANCE_ADJUST_CANCEL",receipt.getId().toString(),"待确认请求已取消，未变更余额",input.getRemark());
  }
  Map<String,Object> result=new LinkedHashMap<>();result.put("status",CANCELLED.equals(receipt.getChanges())?CANCELLED:"COMPLETED");result.put("userId",user.getId());result.put("email",user.getEmail());
  result.put("fundBalance",BigDecimal.ZERO);result.put("contractBalance",BigDecimal.ZERO);result.put("optionBalance",BigDecimal.ZERO);
  for(AssetAccount account:assets.lockByUserId(input.getUserId())){
   String field="FUND".equals(account.getCoin())?"fundBalance":"CONTRACT".equals(account.getCoin())?"contractBalance":"OPTION".equals(account.getCoin())?"optionBalance":null;
   if(field!=null)result.put(field,account.getAvailable()==null?BigDecimal.ZERO:account.getAvailable());
  }
  return result;
 }
}
