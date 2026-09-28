package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.AdminUserRepository;
import com.gtcfesk.exchange.common.TradeValidation;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.security.MessageDigest;

@Service
public class DepositOrderService {
 private final DepositRecordRepository records;
 private final DepositCreditRecordRepository credits;
 private final AssetAccountRepository assets;
 private final UserAccountRepository users;
 private final AdminUserRepository admins;
 private final FiatCurrencyService fiat;
 private final BackendAccess access;
 private final ObjectMapper json;
 private final TransactionTemplate tx;
 public DepositOrderService(DepositRecordRepository records, DepositCreditRecordRepository credits,
   AssetAccountRepository assets, UserAccountRepository users, AdminUserRepository admins,
   FiatCurrencyService fiat, BackendAccess access, ObjectMapper json, PlatformTransactionManager manager) {
  this.records=records; this.credits=credits; this.assets=assets; this.users=users; this.admins=admins;
  this.fiat=fiat; this.access=access; this.json=json; this.tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 public static ResponseStatusException error(int code,String message) { return new ResponseStatusException(HttpStatus.valueOf(code),message); }
 static String text(String value,int max,boolean required) {
  String s=value==null?"":value.trim(); if(s.length()>max || (required && s.isEmpty())) throw error(400,"字段缺失或过长"); return s;
 }
 private static String choice(String value,String... allowed) {
  if(!Arrays.asList(allowed).contains(value)) throw error(400,"无效选项"); return value;
 }
 private String[] actor() {
  Authentication a=SecurityContextHolder.getContext().getAuthentication();
  if(a==null || !a.isAuthenticated()) throw error(401,"请先登录");
  Long agent=BackendAccess.agentId();
  if(agent!=null) return new String[]{"AGENT",agent.toString(),users.findById(agent).map(UserAccount::getEmail).orElseThrow(()->error(401,"操作人不存在"))};
  return new String[]{"ADMIN",a.getName(),admins.findById(Long.valueOf(a.getName())).map(u->u.getAccount()).orElseThrow(()->error(401,"操作人不存在"))};
 }
 private String normalize(DepositOrderRequest r,boolean manual) {
  if(r.userId==null || r.userId<=0) throw error(400,"UID 无效");
  TradeValidation.positive(r.amount,"充值金额");
  r.account=choice(r.account,"FUND","CONTRACT","OPTION"); r.currency=fiat.currency(r.currency);
  r.type=manual?choice(r.type,"manual","bank","digital"):choice(r.type,"bank","digital");
  r.remark=text(r.remark,500,manual); r.address=text(r.address,200,false); r.network=text(r.network,50,false);
  r.proofImage=text(r.proofImage,500,false); r.idempotencyKey=text(r.idempotencyKey,64,manual);
  if(r.idempotencyKey.isEmpty()) r.idempotencyKey=null;
  if(manual) {
   r.manualPurpose=choice(r.manualPurpose,"RECEIPT","BONUS","ADJUSTMENT");
   if("RECEIPT".equals(r.manualPurpose) && ("manual".equals(r.type)||r.proofImage.isEmpty())) throw error(400,"实收补录需要银行/数字渠道及凭证");
  } else {r.manualPurpose=null; r.account="FUND";}
  if("manual".equals(r.type)) {r.address="";r.network="MANUAL";}
  try {
   String canonical=json.writeValueAsString(Arrays.asList(r.userId,r.account,r.currency,r.amount.stripTrailingZeros().toPlainString(),r.type,r.manualPurpose,r.remark,r.address,r.network,r.proofImage));
   byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
   StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format("%02x",b));return hex.toString();
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 private DepositRecord existing(String[] actor,DepositOrderRequest r,String hash) {
  if(r.idempotencyKey==null)return null;
  DepositRecord d=records.findByCreatedByTypeAndCreatedByIdAndIdempotencyKey(actor[0],Long.valueOf(actor[1]),r.idempotencyKey).orElse(null);
  if(d!=null && !hash.equals(d.getRequestHash()))throw error(409,"幂等键已用于不同参数");if(d!=null)d.setIdempotentReplay(true);return d;
 }
 public DepositRecord manual(DepositOrderRequest r) {
  access.checkDeposit("manual_deposit"); access.checkUser(r.userId);
  return create(r,true,actor());
 }
 public DepositRecord submit(Long userId,DepositOrderRequest r) {
  r.userId=userId; return create(r,false,new String[]{"USER",userId.toString(),null});
 }
 private DepositRecord create(DepositOrderRequest r,boolean manual,String[] actor) {
  String hash=normalize(r,manual);
  DepositRecord old=tx.execute(s->existing(actor,r,hash)); if(old!=null)return old;
  try {
   return tx.execute(s->{
    // Serialize with physical user deletion; money writers retain account optimistic locking.
    UserAccount customer=users.lockById(r.userId).orElseThrow(()->error(404,"客户不存在"));
    DepositRecord retry=existing(actor,r,hash); if(retry!=null)return retry;
    BigDecimal rate;
    try {rate=fiat.rate(r.currency);}catch(com.gtcfesk.exchange.common.BusinessException e){throw error(503,"汇率暂不可用");}
    TradeValidation.positive(rate,"汇率");
    DepositRecord d=new DepositRecord(); d.setUserId(r.userId); d.setOrderNo("DEP-"+UUID.randomUUID());
    d.setSource(manual?"ADMIN_MANUAL":"USER_SUBMITTED"); d.setAccountType(r.account);d.setManualPurpose(r.manualPurpose);
    d.setType(r.type);d.setCurrency(r.currency);d.setOriginalAmount(r.amount);d.setExchangeRate(rate);
    d.setFeeRate(BigDecimal.ZERO);d.setFeeAmount(fee(r.amount,BigDecimal.ZERO));d.setAmount(fiat.toUsd(r.amount.subtract(d.getFeeAmount()),rate));
    d.setAddress(r.address);d.setNetwork(r.network);d.setProofImage(r.proofImage);d.setRemark(r.remark);
    d.setCreatedByType(actor[0]);d.setCreatedById(Long.valueOf(actor[1]));d.setCreatedByName("USER".equals(actor[0])?customer.getEmail():actor[2]);
    d.setIdempotencyKey(r.idempotencyKey);d.setRequestHash(hash);
    records.saveAndFlush(d); checkpoint("order");
    if(manual) credit(d,actor);
    records.flush(); checkpoint("flush");return d;
   });
  }catch(org.springframework.dao.DataIntegrityViolationException e){
   DepositRecord retry=tx.execute(s->existing(actor,r,hash));if(retry!=null)return retry;
   throw error(409,"账户或订单并发冲突，请使用原幂等键重试");
  }
 }
 public static BigDecimal fee(BigDecimal amount,BigDecimal rate) {return amount.multiply(rate).setScale(16,RoundingMode.HALF_UP);}
 public DepositRecord review(Long id,boolean approve,String remark) {
  access.checkDepositReview(approve?"approve_deposit":"reject_deposit");
  String note=text(remark,500,!approve);String[] operator=actor();
  try { return tx.execute(s->{
   DepositRecord d=records.findById(id).orElseThrow(()->error(404,"订单不存在"));access.checkUser(d.getUserId());
   if(!"PENDING".equals(d.getStatus()))throw error(409,"订单已处理");
   if("ADMIN_MANUAL".equals(d.getSource()))throw error(409,"手动订单无需审核");
   users.lockById(d.getUserId()).orElseThrow(()->error(404,"客户不存在"));
   d.setReviewedByType(operator[0]);d.setReviewedById(Long.valueOf(operator[1]));d.setReviewedByName(operator[2]);
   d.setReviewedAt(LocalDateTime.now());d.setReviewRemark(note);
   if(approve)credit(d,operator);else d.setStatus("REJECTED");
   records.saveAndFlush(d); checkpoint("flush");return d;
  }); } catch(org.springframework.dao.DataIntegrityViolationException e) { throw error(409,"订单或账户已变更，请刷新后重试"); }
 }
 private void credit(DepositRecord d,String[] operator) {
  TradeValidation.positive(d.getAmount(),"入账金额");
  String account=d.getAccountType()==null?"FUND":choice(d.getAccountType(),"FUND","CONTRACT","OPTION");
  AssetAccount a=assets.findByUserIdAndCoin(d.getUserId(),account).orElseGet(()->{AssetAccount n=new AssetAccount();n.setUserId(d.getUserId());n.setCoin(account);return n;});
  BigDecimal before=a.getAvailable(), after=before.add(d.getAmount());TradeValidation.positive(after,"入账后余额");
  a.setAvailable(after);assets.saveAndFlush(a);checkpoint("account");
  LocalDateTime now=LocalDateTime.now();DepositCreditRecord c=new DepositCreditRecord();
  c.setDepositRecordId(d.getId());c.setUserId(d.getUserId());c.setAccountType(account);c.setAmountUsd(d.getAmount());
  c.setBalanceBefore(before);c.setBalanceAfter(after);c.setOperatorType(operator[0]);c.setOperatorId(Long.valueOf(operator[1]));c.setOperatorName(operator[2]);c.setCreditedAt(now);
  credits.saveAndFlush(c);checkpoint("credit");d.setStatus("COMPLETED");d.setCreditedAt(now);d.setAccountType(account);
 }
 /** Fault injection seam for isolated transaction tests; production is a no-op. */
 protected void checkpoint(String stage) { }
 public static Map<String,Object> publicDto(DepositRecord d) {
  Map<String,Object> out=new LinkedHashMap<>();
  out.put("id",d.getId());out.put("userId",d.getUserId());out.put("type",d.getType());out.put("network",d.getNetwork());
  out.put("amount",decimal(d.getAmount()));out.put("currency",d.getCurrency());out.put("originalAmount",decimal(d.getOriginalAmount()));out.put("exchangeRate",decimal(d.getExchangeRate()));
  out.put("address",d.getAddress());out.put("proofImage",d.getProofImage());out.put("status",d.getStatus());
  out.put("remark","ADMIN_MANUAL".equals(d.getSource())?null:d.getRemark());out.put("createdAt",d.getCreatedAt());out.put("updatedAt",d.getUpdatedAt());
  out.put("orderNo",d.getOrderNo());out.put("source",d.getSource()==null?"LEGACY_UNKNOWN":d.getSource());return out;
 }
 public static String decimal(BigDecimal value){return value==null?null:value.toPlainString();}
}
