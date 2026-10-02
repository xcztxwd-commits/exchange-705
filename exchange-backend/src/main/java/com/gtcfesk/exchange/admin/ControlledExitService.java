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
 public static class Input {public String requestId,reason;public java.math.BigDecimal stopLoss,takeProfit;
  @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("受控退出不接受额外字段: "+name);}
 }
 @Transactional public Map<String,Object> execute(String kind,Long id,String command,Input input){
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
  // Match financial settlement's existing order-before-user lock order. Other engines lock the user first.
  Object order="contract".equals(kind)?contracts.findByTenantIdAndId(tenant,id).orElseThrow(()->new BusinessException("订单不存在")):"option".equals(kind)?options.findByTenantIdAndId(tenant,id).orElseThrow(()->new BusinessException("订单不存在")):"financial".equals(kind)?financialOrders.lockById(id).orElseThrow(()->new BusinessException("订单不存在")):loanOrders.lockById(id).orElseThrow(()->new BusinessException("订单不存在"));
  Long user=order instanceof ContractOrder?((ContractOrder)order).getUserId():order instanceof OptionOrder?((OptionOrder)order).getUserId():order instanceof FinancialOrder?((FinancialOrder)order).getUserId():((LoanRecord)order).getUserId();
  users.lockById(user).orElseThrow(()->new BusinessException("用户不存在"));
  String hash=com.gtcfesk.exchange.support.SupportService.sha256(bytes(Arrays.asList(kind,id,command,actorType,actor,input.reason,input.stopLoss,input.takeProfit)));
  BalanceAdjustment prior=receipts.findByTenantIdAndRequestKey(tenant,input.requestId).orElse(null);
  if(prior!=null){if(!hash.equals(prior.getRequestHash()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"幂等键已用于不同命令");return decode(prior.getChanges());}
  em.refresh(order,LockModeType.PESSIMISTIC_WRITE);
  if(order instanceof ContractOrder&&(!"USER".equals(((ContractOrder)order).getOrderSource())||((ContractOrder)order).isDeleted())||order instanceof OptionOrder&&((OptionOrder)order).isDeleted())throw new BusinessException("该订单不能通过受控退出处理");
  Map<String,Object> before=snapshot(user,order);
  if("contract".equals(kind)){if("close".equals(command))order=contractService.adminCloseOrder(id,null);else if("cancel".equals(command))order=contractService.adminCancelOrder(id);else order=contractService.updateStopLossTakeProfit(user,id,input.stopLoss,input.takeProfit);}
  else if("option".equals(kind))order=optionService.closeOrder(user,id,null);
  else if("financial".equals(kind))order=financial.earlyRedeem(user,id);
  else order=loanService.earlyRepayment(id,user);
  Map<String,Object> result=new LinkedHashMap<>();result.put("success",true);result.put("tenantId",tenant);result.put("kind",kind);result.put("orderId",id);result.put("command",command);result.put("actorType",actorType);result.put("actorId",actor);result.put("before",before);result.put("after",snapshot(user,order));
  BalanceAdjustment receipt=new BalanceAdjustment();receipt.setUserId(user);receipt.setRequestKey(input.requestId);receipt.setRequestHash(hash);receipt.setActorType(actorType);receipt.setActorId(actor);receipt.setReason(input.reason);receipt.setChanges(new String(bytes(result),java.nio.charset.StandardCharsets.UTF_8));receipts.saveAndFlush(receipt);
  if(control!=null)audit.record(actor,tenant,control.getAccessSessionId(),"CONTROLLED_EXIT",kind+":"+id,"SUCCESS",receipt.getChanges(),input.reason);
  else {OperationLog log=new OperationLog();log.setAdminId(actor);log.setAdminEmail(admin.getEmail());log.setOperationType("受控退出");log.setOperationAction(kind+":"+command);log.setTargetType(kind);log.setTargetId(id);log.setTargetInfo("userId="+user+"; reason="+input.reason);log.setRequestMethod("POST");log.setRequestUrl("/api/admin/controlled-exits/"+kind+"/"+id+"/"+command);log.setRequestParams(receipt.getChanges());logs.saveAndFlush(log);}
  return result;
 }
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
