package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.trade.*;
import com.gtcfesk.exchange.user.FinancialService;
import com.gtcfesk.exchange.activity.TrialFunds;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import javax.persistence.*;
import java.util.*;

/** D01-A: explicit existing-object commands, never fake KYC, employees or caller-supplied ownership. */
@Service @RequiredArgsConstructor
public class ControlledExitService {
 private final ContractOrderRepository contracts; private final OptionOrderRepository options;
 private final FinancialOrderRepository financialOrders; private final LoanRecordRepository loanOrders; private final UserAccountRepository users;
 private final AssetAccountRepository assets; private final BalanceAdjustmentRepository receipts;
 private final OperationLogRepository logs; private final AdminUserRepository admins;
 private final ContractOrderService contractService; private final OptionOrderService optionService;
 private final FinancialService financial; private final com.gtcfesk.exchange.user.LoanService loanService; private final TrialFunds trial; private final ControlAuditService audit;
 private final AdminPermissionService permissions; private final ObjectMapper json;
 @PersistenceContext private EntityManager em;
 @org.springframework.beans.factory.annotation.Value("${app.market.s3-scheduling-enabled:false}") private boolean s3SchedulingEnabled;
 @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.market.FundingQuoteAuthority quoteAuthority;
 public static class Input {public String requestId,reason;public java.math.BigDecimal stopLoss,takeProfit;
  @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("受控退出不接受额外字段: "+name);}
 }
 /** Preserve one whole command commit; only enabled price-based closes prepare outside it. */
 public Map<String,Object> execute(String kind,Long id,String command,Input input){
  boolean preparedClose=s3SchedulingEnabled&&"close".equals(command)&&("contract".equals(kind)||"option".equals(kind));
  org.springframework.transaction.support.TransactionTemplate transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  if(!preparedClose)return transaction.execute(status->executeLocked(kind,id,command,input,null,false));
  if(quoteAuthority==null)throw new BusinessException("S3_QUOTE_REJECTED: 受控退出报价权威不可用");
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new BusinessException("S3_QUOTE_REJECTED: 受控退出准备不得加入外层资金事务");
  transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  Map<String,Object> prior=transaction.execute(status->executeLocked(kind,id,command,input,null,true));if(prior!=null)return prior;
  Map<String,Object> prepared;
  try{
   if("contract".equals(kind))prepared=contractService.prepareCloseQuote(id);
   else {OptionOrder route=options.findByTenantIdAndId(TenantContext.requireTenantId(),id).orElseThrow(()->new BusinessException("订单不存在"));prepared=quoteAuthority.prepare(route.getSymbol());}
  }catch(BusinessException unavailable){Map<String,Object> committed=transaction.execute(status->executeLocked(kind,id,command,input,null,true));if(committed!=null)return committed;throw unavailable;}
  final Map<String,Object> candidate=prepared;
  return transaction.execute(status->executeLocked(kind,id,command,input,candidate,false));
 }
 private Map<String,Object> executeLocked(String kind,Long id,String command,Input input,Map<String,Object> prepared,boolean receiptOnly){
  Long tenant=TenantContext.requireTenantId();
  org.springframework.security.core.Authentication auth=SecurityContextHolder.getContext().getAuthentication();
  if(auth==null||!auth.isAuthenticated()||auth.getAuthorities().stream().noneMatch(a->Arrays.asList("ROLE_ADMIN","ROLE_SUPER_ADMIN").contains(a.getAuthority()))||auth.getAuthorities().stream().anyMatch(a->"ROLE_AGENT".equals(a.getAuthority())))throw new AccessDeniedException("受控退出需要管理员或目标租户总控访问身份");
  ControlIdentity control=ControlIdentity.current();Long actor;String actorType;AdminUser admin=null;
  if(control!=null){if(control.getAccessSessionId()==null||control.getActorId()==null||control.getActorId()<=0||!tenant.equals(control.getTenantId()))throw new AccessDeniedException("目标租户身份不匹配");actor=control.getActorId();actorType="CONTROL";}
  else {try{actor=Long.valueOf(auth.getName());}catch(NumberFormatException e){throw new AccessDeniedException("操作者无效");}admin=admins.findByTenantIdAndId(tenant,actor).filter(a->Boolean.TRUE.equals(a.getEnabled())).orElseThrow(()->new AccessDeniedException("操作者已停用"));actorType="ADMIN";}
  if(id==null||id<=0||input==null||input.requestId==null||!input.requestId.matches("[a-zA-Z0-9_-]{16,64}")||input.reason==null||input.reason.trim().length()<5||input.reason.length()>500)throw new IllegalArgumentException("需要16至64位幂等键和5至500字处理原因");
  String permission;if("contract".equals(kind)&&Arrays.asList("close","cancel","risk").contains(command))permission=command.equals("cancel")?"cancel_order":command.equals("risk")?"risk_exit":"close_order";
  else if("option".equals(kind)&&"close".equals(command))permission="close_order";
  else if(("financial".equals(kind)&&"redeem".equals(command))||("loan".equals(kind)&&"repay".equals(command)))permission="controlled_exit";
  else throw new IllegalArgumentException("受控命令无效");
  if(!"risk".equals(command)&&(input.stopLoss!=null||input.takeProfit!=null))throw new IllegalArgumentException("非风控命令不接受止盈止损字段");
  permissions.require("financial".equals(kind)?"financial_orders":"loan".equals(kind)?"loan_review":"orders",permission);
  // Scalar ownership is a routing hint only; no order entity or lock before the user funds lock.
  String entity="contract".equals(kind)?"ContractOrder":"option".equals(kind)?"OptionOrder":"financial".equals(kind)?"FinancialOrder":"LoanRecord";
  List<Long> owners=em.createQuery("select o.userId from "+entity+" o where o.tenantId=:tenant and o.id=:id",Long.class)
   .setParameter("tenant",tenant).setParameter("id",id).getResultList();
  if(owners.isEmpty())throw new BusinessException("订单不存在");Long user=owners.get(0);
  // TrialFunds refreshes the user before Hibernate can upgrade a stale cached @Version.
  // Maintenance locks grants and its pending-order set in stable ID order before the target order.
  boolean authorizedClose=s3SchedulingEnabled&&"close".equals(command)&&("contract".equals(kind)||"option".equals(kind));
  if(authorizedClose)trial.lockForQuoteAndPendingExpiry(user);else trial.lock(user);
  String hash=com.gtcfesk.exchange.support.SupportService.sha256(bytes(Arrays.asList(kind,id,command,actorType,actor,input.reason,input.stopLoss,input.takeProfit)));
  BalanceAdjustment prior=receipts.findByTenantIdAndRequestKey(tenant,input.requestId).orElse(null);
  if(prior!=null){em.refresh(prior,LockModeType.PESSIMISTIC_READ);if(!hash.equals(prior.getRequestHash()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"幂等键已用于不同命令");return decode(prior.getChanges());}
  if(receiptOnly)return null;
  permissions.require("financial".equals(kind)?"financial_orders":"loan".equals(kind)?"loan_review":"orders",permission);
  em.flush();em.createQuery("select o from "+entity+" o where o.tenantId=:tenant and o.id=:id",Object.class).setParameter("tenant",tenant).setParameter("id",id).getResultList().forEach(row->em.refresh(row,LockModeType.PESSIMISTIC_WRITE));
  Object order="contract".equals(kind)?contracts.lockById(id).orElseThrow(()->new BusinessException("订单不存在")):"option".equals(kind)?options.lockById(id).orElseThrow(()->new BusinessException("订单不存在")):"financial".equals(kind)?financialOrders.lockById(id).orElseThrow(()->new BusinessException("订单不存在")):loanOrders.lockById(id).orElseThrow(()->new BusinessException("订单不存在"));
  em.refresh(order,LockModeType.PESSIMISTIC_WRITE);
  Long currentOwner=order instanceof ContractOrder?((ContractOrder)order).getUserId():order instanceof OptionOrder?((OptionOrder)order).getUserId():order instanceof FinancialOrder?((FinancialOrder)order).getUserId():((LoanRecord)order).getUserId();
  if(!user.equals(currentOwner))throw new BusinessException("订单归属已变更，请重试");
  if(order instanceof ContractOrder&&(!"USER".equals(((ContractOrder)order).getOrderSource())||((ContractOrder)order).isDeleted())||order instanceof OptionOrder&&((OptionOrder)order).isDeleted())throw new BusinessException("该订单不能通过受控退出处理");
  if(admin!=null){em.refresh(admin,LockModeType.PESSIMISTIC_READ);if(!tenant.equals(admin.getTenantId())||!Boolean.TRUE.equals(admin.getEnabled()))throw new AccessDeniedException("操作者已停用或目标租户身份不匹配");}
  Map<String,Object> before;
  if(authorizedClose){
   if(order instanceof ContractOrder&&!"OPEN".equals(((ContractOrder)order).getStatus())||order instanceof OptionOrder&&!"TRADING".equals(((OptionOrder)order).getStatus()))throw new BusinessException("订单状态不正确，无法受控平仓");
   final Object lockedOrder=order;final Map<String,Object> captured=new LinkedHashMap<>();
   Runnable beforeSettlement=()->captured.putAll(snapshot(user,lockedOrder));
   if("contract".equals(kind))order=contractService.closePrepared(user,id,prepared,beforeSettlement);
   else order=optionService.closePrepared(user,id,prepared,beforeSettlement);
   if(captured.isEmpty())throw new IllegalStateException("受控退出未执行授权后的原子快照");before=captured;
  }else{
   before=snapshot(user,order);
   if("contract".equals(kind)){if("close".equals(command))order=contractService.adminCloseOrder(id,null);else if("cancel".equals(command))order=contractService.adminCancelOrder(id);else order=contractService.updateStopLossTakeProfit(user,id,input.stopLoss,input.takeProfit);}
   else if("option".equals(kind))order=optionService.closeOrder(user,id,null);
   else if("financial".equals(kind))order=financial.earlyRedeem(user,id);
   else order=loanService.earlyRepayment(id,user);
  }
  Map<String,Object> result=new LinkedHashMap<>();result.put("success",true);result.put("tenantId",tenant);result.put("kind",kind);result.put("orderId",id);result.put("command",command);result.put("actorType",actorType);result.put("actorId",actor);result.put("before",before);result.put("after",snapshot(user,order));
  BalanceAdjustment receipt=new BalanceAdjustment();receipt.setUserId(user);receipt.setRequestKey(input.requestId);receipt.setRequestHash(hash);receipt.setActorType(actorType);receipt.setActorId(actor);receipt.setReason(input.reason);receipt.setChanges(new String(bytes(result),java.nio.charset.StandardCharsets.UTF_8));receipts.saveAndFlush(receipt);checkpoint("controlled-receipt");
  if(control!=null)audit.record(actor,tenant,control.getAccessSessionId(),"CONTROLLED_EXIT",kind+":"+id,"SUCCESS",receipt.getChanges(),input.reason);
  else {OperationLog log=new OperationLog();log.setAdminId(actor);log.setAdminEmail(admin.getEmail());log.setOperationType("受控退出");log.setOperationAction(kind+":"+command);log.setTargetType(kind);log.setTargetId(id);log.setTargetInfo("userId="+user+"; reason="+input.reason);log.setRequestMethod("POST");log.setRequestUrl("/api/admin/controlled-exits/"+kind+"/"+id+"/"+command);log.setRequestParams(receipt.getChanges());logs.saveAndFlush(log);}
  checkpoint("controlled-audit");return result;
 }
 protected void checkpoint(String stage) { }
 private Map<String,Object> snapshot(Long user,Object order){
  Map<String,Object> out=new LinkedHashMap<>();Map<String,Object> balances=new LinkedHashMap<>();
  for(AssetAccount a:assets.lockByUserId(user))if(Arrays.asList("FUND","CONTRACT","OPTION").contains(a.getCoin())){Map<String,Object>b=new LinkedHashMap<>();b.put("available",a.getAvailable());b.put("frozen",a.getFrozen());balances.put(a.getCoin(),b);}
  out.put("balances",balances);com.gtcfesk.exchange.activity.TrialAccount t=trial.snapshot(user);out.put("trialAvailable",t.getAvailable());out.put("trialFrozen",t.getFrozen());
  out.put("status",order instanceof ContractOrder?((ContractOrder)order).getStatus():order instanceof OptionOrder?((OptionOrder)order).getStatus():order instanceof FinancialOrder?((FinancialOrder)order).getStatus():((LoanRecord)order).getStatus());
  if(order instanceof ContractOrder){ContractOrder c=(ContractOrder)order;out.put("stopLoss",c.getStopLoss());out.put("takeProfit",c.getTakeProfit());out.put("closePrice",c.getClosePrice());out.put("profit",c.getProfit());out.put("fee",c.getFee());out.put("margin",c.getMargin());out.put("trialReserved",c.getTrialReserved());}
  else if(order instanceof OptionOrder){OptionOrder o=(OptionOrder)order;out.put("closePrice",o.getClosePrice());out.put("profit",o.getProfit());out.put("amount",o.getAmount());out.put("trialReserved",o.getTrialReserved());}
  else if(order instanceof FinancialOrder){FinancialOrder f=(FinancialOrder)order;out.put("purchaseAmount",f.getPurchaseAmount());out.put("penaltyRate",f.getPenaltyRate());out.put("penaltyAmount",f.getPenaltyAmount());out.put("totalYield",f.getTotalYield());}
  else {LoanRecord l=(LoanRecord)order;out.put("amount",l.getAmount());out.put("totalInterest",l.getTotalInterest());out.put("repaymentAmount",l.getRepaymentAmount());out.put("approvedAt",l.getApprovedAt()==null?null:l.getApprovedAt().toString());out.put("actualRepaymentAt",l.getActualRepaymentAt()==null?null:l.getActualRepaymentAt().toString());}return out;
 }
 private byte[] bytes(Object value){try{return json.writeValueAsBytes(value);}catch(java.io.IOException e){throw new IllegalStateException("受控退出记录序列化失败",e);}}
 @SuppressWarnings("unchecked") private Map<String,Object> decode(String value){try{return json.readValue(value,Map.class);}catch(java.io.IOException e){throw new IllegalStateException("受控退出收据损坏",e);}}
}
