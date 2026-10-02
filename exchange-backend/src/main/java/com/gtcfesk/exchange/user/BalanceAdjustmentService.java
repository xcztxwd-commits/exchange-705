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
 private final UserAccountRepository users;private final AssetAccountRepository assets;
 private final BalanceAdjustmentRepository adjustments;private final ControlAuditService audit;
 @Transactional public void adjust(UpdateUserBalanceRequest input){
  Authentication auth=SecurityContextHolder.getContext().getAuthentication();
  if(auth==null||auth.getAuthorities().stream().noneMatch(a->"ROLE_SUPER_ADMIN".equals(a.getAuthority())))throw new org.springframework.security.access.AccessDeniedException("无权调整资金");
  if(!input.isConfirm()||input.getRemark()==null||input.getRemark().trim().length()<3||input.getRemark().length()>500||input.getIdempotencyKey()==null||!input.getIdempotencyKey().matches("[A-Za-z0-9_-]{16,64}"))throw new IllegalArgumentException("必须确认调整、填写原因并提交幂等键");
  Long tenant=TenantContext.requireTenantId();users.lockById(input.getUserId()).orElseThrow(()->new IllegalArgumentException("用户不存在"));
  String actorType=ControlIdentity.isAccess()?"CONTROL":"ADMIN";Long actor=ControlIdentity.isAccess()?ControlIdentity.actorId():Long.valueOf(auth.getName());
  String canonical=Arrays.asList(input.getUserId(),input.getFundBalance(),input.getContractBalance(),input.getOptionBalance(),input.getRemark(),actorType,actor).toString();
  String hash=SupportService.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  BalanceAdjustment previous=adjustments.findByTenantIdAndRequestKey(tenant,input.getIdempotencyKey()).orElse(null);
  if(previous!=null){if(!previous.getRequestHash().equals(hash))throw new IllegalArgumentException("幂等键已用于不同调整");return;}
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
}
