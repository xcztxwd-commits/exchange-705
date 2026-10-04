package com.gtcfesk.exchange.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.user.KycIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** Promotional principal remains separate from transferable asset accounts. */
@Service
public class TrialFunds {
 private final TrialAccountRepository trials;
 private final TrialGrantRepository grants;
 private final TrialLedgerRepository ledger;
 private final UserAccountRepository users;
 private final AssetAccountRepository assets;
 private final KycIdentityService identity;
 @org.springframework.beans.factory.annotation.Autowired
 public TrialFunds(TrialAccountRepository trials,TrialGrantRepository grants,TrialLedgerRepository ledger,UserAccountRepository users,AssetAccountRepository assets,KycIdentityService identity){
  this.trials=trials;this.grants=grants;this.ledger=ledger;this.users=users;this.assets=assets;this.identity=identity;
 }
 /** Compatibility constructor for existing cash-only isolated tests. */
 public TrialFunds(TrialAccountRepository trials,TrialLedgerRepository ledger,UserAccountRepository users,AssetAccountRepository assets,KycIdentityService identity){
  this(trials,null,ledger,users,assets,identity);
 }
 @PersistenceContext private EntityManager entityManager;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private Clock clock=Clock.systemUTC();
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final BigDecimal ZERO=BigDecimal.ZERO;
 public LocalDateTime now(){return LocalDateTime.now(clock);}
 public boolean canTrade(Long user){return identity.canUseTradingFunds(user);}
 public void requireTrade(Long user){identity.requireTradingApproved(user);}
 private Long tenant(){return com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();}

 /** Lock order: user, asset rows, trial account/grants, then pending orders. */
 /** Current funding anchors only: no LEGACY grants, expiry maintenance or money DML. */
 @Transactional public void lockForQuote(Long user){TrialAccount a=lockAccount(user,false);if(a!=null)grantRows(a,false);}
 /** Quote units whose later expiry maintenance can cancel unmatched TRIAL contract orders.
  * Keep the existing Option lockForQuote sequence unchanged; this method adds only prelocks, no expiry DML. */
 @Transactional public void lockForQuoteAndPendingExpiry(Long user){
  TrialAccount a=lockAccount(user,false);if(a==null)return;
  List<TrialGrant> rows=grantRows(a,false);
  if(rows.stream().noneMatch(g->g.isActive()&&g.getExpiresAt()!=null))return;
  org.hibernate.query.NativeQuery<?> query=entityManager.createNativeQuery("select * from contract_order where tenant_id=?1 and user_id=?2 and status='PENDING' and funding_source='TRIAL' order by id for update").unwrap(org.hibernate.query.NativeQuery.class);
  query.addEntity("locked",ContractOrder.class,org.hibernate.LockMode.NONE);query.setParameter(1,tenant());query.setParameter(2,user);
  for(Object loaded:query.getResultList()){
   ContractOrder order=(ContractOrder)loaded;entityManager.refresh(order,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
   com.gtcfesk.exchange.tenant.TenantContext.require(order.getTenantId());if(!user.equals(order.getUserId()))throw new BusinessException("挂单归属已变更，请重试");
  }
 }
 @Transactional public void lock(Long user){
  TrialAccount a=lockAccount(user,false);if(a!=null){List<TrialGrant> rows=grantRows(a);if(a.isTrialEligible())maintainLocked(a,rows);}
 }
 private TrialAccount lockAccount(Long user,boolean create){
  if(entityManager!=null){entityManager.flush();com.gtcfesk.exchange.entity.UserAccount loaded=users.findByTenantIdAndId(tenant(),user).orElseThrow(()->new BusinessException("用户不存在"));entityManager.refresh(loaded,javax.persistence.LockModeType.PESSIMISTIC_WRITE);}
  com.gtcfesk.exchange.entity.UserAccount customer=users.lockById(user).orElseThrow(()->new BusinessException("用户不存在"));
  // A repeat lock in a multi-order transaction must preserve earlier, still-uncommitted writes.
  if(entityManager!=null){entityManager.flush();entityManager.refresh(customer,javax.persistence.LockModeType.PESSIMISTIC_WRITE);}
  if(entityManager!=null){entityManager.flush();List<AssetAccount> loaded=new ArrayList<>(assets.findByTenantIdAndUserId(tenant(),user));loaded.sort(Comparator.comparing(AssetAccount::getCoin).thenComparing(AssetAccount::getId));for(AssetAccount row:loaded){entityManager.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_WRITE);com.gtcfesk.exchange.tenant.TenantContext.require(row.getTenantId());if(!user.equals(row.getUserId()))throw new BusinessException("账户归属已变更，请重试");}}
  List<AssetAccount> cash=assets.lockByUserId(user);
  if(entityManager!=null){entityManager.flush();for(AssetAccount row:cash)entityManager.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_WRITE);}
  return create?account(user):refreshTrial(user);
 }
 /** Anchor only before the campaign lock; grant ranges and expiry maintenance follow that shared lock. */
 @Transactional public void lockForGrant(Long user){lockAccount(user,true);}
 private TrialAccount account(Long user){
  if(entityManager!=null){
   entityManager.flush();
   // Existing repository upsert pattern: exclusive row anchor, never a missing-row FOR UPDATE gap.
   entityManager.createNativeQuery("insert into trial_account(tenant_id,user_id,row_version,available,frozen,granted,consumed,profits,expired,uncovered_loss,trial_eligible) values(:tenant,:user,0,0,0,0,0,0,0,0,false) on duplicate key update user_id=case when tenant_id=:tenant then user_id else null end")
    .setParameter("tenant",tenant()).setParameter("user",user).executeUpdate();checkpoint("trial-anchor");
  }
  refreshTrial(user);TrialAccount a=trials.lock(user).orElseGet(()->{TrialAccount next=new TrialAccount();next.setUserId(user);TrialAccount saved=trials.saveAndFlush(next);checkpoint("new-trial-account");return saved;});
  if(entityManager!=null){entityManager.flush();entityManager.refresh(a,javax.persistence.LockModeType.PESSIMISTIC_WRITE);}return a;
 }
 private TrialAccount refreshTrial(Long user){
  if(entityManager==null)return trials.lock(user).orElse(null);
  entityManager.flush();TrialAccount row=trials.findByTenantIdAndId(tenant(),user).orElse(null);
  // A miss is only a routing hint. Actual account writers upsert then perform the authoritative current read.
  if(row!=null){entityManager.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_WRITE);com.gtcfesk.exchange.tenant.TenantContext.require(row.getTenantId());if(!user.equals(row.getUserId()))throw new BusinessException("体验金账户归属已变更，请重试");}
  return row;
 }
 private List<TrialGrant> grantRows(TrialAccount a){return grantRows(a,true);}
 private List<TrialGrant> grantRows(TrialAccount a,boolean createLegacy){
  // A current zero anchor has no grant history. Do not replace the account gap with a missing-child range gap.
  if(!a.isTrialEligible()&&Arrays.asList(a.getAvailable(),a.getFrozen(),a.getGranted(),a.getConsumed(),a.getExpired(),a.getProfits(),a.getUncoveredLoss()).stream().allMatch(value->value.signum()==0))return new ArrayList<>();
  List<TrialGrant> rows;
  if(entityManager==null)rows=grants.lockByUserId(a.getUserId());
  else {
   entityManager.flush();
   // Hydrate full current rows before refreshing an old managed version or an uninitialized RR proxy.
   // NONE is ORM hydration only: SQL still takes the tenant/user grant rows in stable ID order.
   org.hibernate.query.NativeQuery<?> query=entityManager.createNativeQuery("select * from trial_grant where tenant_id=?1 and user_id=?2 order by id for update").unwrap(org.hibernate.query.NativeQuery.class);
   query.addEntity("locked",TrialGrant.class,org.hibernate.LockMode.NONE);query.setParameter(1,tenant());query.setParameter(2,a.getUserId());
   rows=new ArrayList<>();
   for(Object loaded:query.getResultList()){
    TrialGrant row=TrialGrant.class.cast(loaded);entityManager.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
    com.gtcfesk.exchange.tenant.TenantContext.require(row.getTenantId());if(!a.getUserId().equals(row.getUserId()))throw new BusinessException("体验金批次归属已变更，请重试");rows.add(row);
   }
  }
  if(createLegacy&&rows.isEmpty()&&(a.getAvailable().signum()!=0||a.getFrozen().signum()!=0||a.getGranted().signum()!=0)){
   // Historical balances have no per-claim clock. Preserve, never invent an expiry.
   TrialGrant old=new TrialGrant();old.setUserId(a.getUserId());old.setRequestKey("LEGACY");old.setClaimedAt(now());
   old.setAvailable(a.getAvailable());old.setFrozen(a.getFrozen());old.setConsumed(a.getConsumed());
   rows.add(grants.saveAndFlush(old));checkpoint("legacy-grant");
  }
  return rows;
 }
 private void maintainLocked(TrialAccount a){maintainLocked(a,grantRows(a));}
 private void maintainLocked(TrialAccount a,List<TrialGrant> rows){
  LocalDateTime instant=now();boolean changed=false;
  for(TrialGrant g:rows)if(g.isActive()&&g.getExpiresAt()!=null&&!instant.isBefore(g.getExpiresAt())){
   g.setActive(false);BigDecimal unused=g.getAvailable();g.setAvailable(ZERO);g.setExpired(g.getExpired().add(unused));
   a.setAvailable(a.getAvailable().subtract(unused));a.setExpired(a.getExpired().add(unused));grants.save(g);checkpoint("expiration-grant");changed=true;
  }
  if(changed){
   // D01: cancel only unmatched TRIAL pending orders. Never close an open position.
   List<ContractOrder> loaded=entityManager.createQuery("select o from ContractOrder o where o.tenantId=:tenant and o.userId=:user and o.status='PENDING' and o.fundingSource='TRIAL' order by o.id",ContractOrder.class).setParameter("tenant",tenant()).setParameter("user",a.getUserId()).getResultList();
   for(ContractOrder row:loaded)entityManager.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
   List<ContractOrder> pending=entityManager.createQuery("select o from ContractOrder o where o.tenantId=:tenant and o.userId=:user and o.status='PENDING' and o.fundingSource='TRIAL' order by o.id",ContractOrder.class)
    .setParameter("tenant",tenant()).setParameter("user",a.getUserId()).setLockMode(javax.persistence.LockModeType.PESSIMISTIC_WRITE).getResultList();
   for(ContractOrder o:pending){
    entityManager.refresh(o,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
    if(!tenant().equals(o.getTenantId())||!a.getUserId().equals(o.getUserId())||!"PENDING".equals(o.getStatus())||!"TRIAL".equals(o.getFundingSource()))continue;
    Map<Long,BigDecimal> portions=decode(o.getTrialAllocations());
    if(portions.keySet().stream().anyMatch(id->rows.stream().anyMatch(g->g.getId().equals(id)&&!g.isActive()))){
     release(a,rows,portions);o.setStatus("CANCELLED");entityManager.persist(o);checkpoint("expiration-order");
     record(a,ZERO,"TRIAL_EXPIRED_CANCEL:"+o.getId());
    }
   }
  }
  a.setTrialEligible(rows.stream().anyMatch(g->g.isActive()&&(g.getAvailable().signum()>0||g.getFrozen().signum()>0)));
  trials.save(a);checkpoint("maintenance-account");
  // A later order refresh must see the cancellation, not overwrite an unflushed state.
  // Flush stays inside this transaction: grants, funds and order status still commit or roll back together.
  if(changed)entityManager.flush();
 }
 @Transactional public TrialAccount snapshot(Long user){
  TrialAccount a=trials.findByTenantIdAndId(tenant(),user).orElse(null);
  if(a==null){TrialAccount empty=new TrialAccount();empty.setUserId(user);return empty;}
  if(a.isTrialEligible()){lock(user);return trials.findByTenantIdAndId(tenant(),user).orElse(a);}
  return a;
 }
 @Transactional public BigDecimal available(Long user){return snapshot(user).getAvailable();}
 @Transactional public Map<String,Object> status(Long user){
  TrialAccount a=trials.findByTenantIdAndId(tenant(),user).orElse(null);List<TrialGrant> rows=Collections.emptyList();
  if(a!=null){lock(user);a=trials.lock(user).orElseThrow(()->new BusinessException("体验金账户已变更"));rows=grantRows(a);}
  else {a=new TrialAccount();a.setUserId(user);}
  LocalDateTime expiry=rows.stream().filter(g->g.isActive()&&g.getExpiresAt()!=null).map(TrialGrant::getExpiresAt).min(LocalDateTime::compareTo).orElse(null);
  Map<String,Object> out=new LinkedHashMap<>();out.put("trialEligible",a.isTrialEligible());out.put("trialExpiresAt",expiry);
  out.put("serverNow",now());out.put("trialAvailable",a.getAvailable());out.put("trialFrozen",a.getFrozen());
  out.put("fundingSources",a.isTrialEligible()?Arrays.asList("TRIAL","CONTRACT","OPTION"):Arrays.asList("CONTRACT","OPTION"));
  out.put("grants",rows);return out;
 }
 public void record(TrialAccount a,BigDecimal delta,String reason){
  trials.save(a);checkpoint("trial-account");TrialLedger line=new TrialLedger();line.setUserId(a.getUserId());line.setAvailable(a.getAvailable());
  line.setFrozen(a.getFrozen());line.setDelta(delta);line.setReason(reason);ledger.save(line);checkpoint("trial-ledger");
 }
 /** Current receipt after the canonical user lock; an absent request key never takes its own gap lock. */
 @Transactional public TrialGrant currentGrant(Long user,String key){
  TrialAccount a=lockAccount(user,true);List<TrialGrant> rows=grantRows(a);if(a.isTrialEligible())maintainLocked(a,rows);
  return rows.stream().filter(row->Objects.equals(key,row.getRequestKey())).findFirst().orElse(null);
 }
 @Transactional public TrialAccount grant(Long user,BigDecimal amount,Long delivery){return grant(user,amount,null,delivery,"delivery-"+delivery,null);}
 @Transactional public TrialAccount grant(Long user,BigDecimal amount,Long campaign,Long delivery,String key,Integer days){
  if(amount==null||amount.signum()<=0||key==null||key.length()>80)throw new BusinessException("领取参数无效");
  TrialAccount a=lockAccount(user,true);List<TrialGrant> rows=grantRows(a);if(a.isTrialEligible())maintainLocked(a,rows);
  TrialGrant prior=rows.stream().filter(row->key.equals(row.getRequestKey())).findFirst().orElse(null);
  // prior already belongs to the WRITE-locked current set; a weaker refresh can re-enter the old RR snapshot.
  if(prior!=null){if(!Objects.equals(prior.getCampaignId(),campaign))throw new BusinessException("幂等键已用于其他活动");return a;}
  LocalDateTime claimedAt=now();TrialGrant g=new TrialGrant();g.setUserId(user);g.setCampaignId(campaign);g.setDeliveryId(delivery);
  g.setRequestKey(key);g.setClaimedAt(claimedAt);g.setExpiresAt(days==null?null:claimedAt.plusDays(days));g.setAvailable(amount);grants.saveAndFlush(g);checkpoint("claim-grant");
  a.setAvailable(a.getAvailable().add(amount));a.setGranted(a.getGranted().add(amount));a.setTrialEligible(true);
  record(a,amount,"CLAIM:"+delivery+":"+g.getId());return a;
 }
 public String source(String value,String expected){
  String chosen=value==null||value.isEmpty()?expected:value;
  if(!"TRIAL".equals(chosen)&&!expected.equals(chosen))throw new BusinessException("资金账户无效");
  return chosen;
 }
 public static class Reservation {
  public final BigDecimal trial;public final String allocations;
  public Reservation(BigDecimal trial,String allocations){this.trial=trial;this.allocations=allocations;}
 }
 public Reservation reserve(Long user,AssetAccount cash,BigDecimal cost,String source,String expected,String reason){
  if(cost==null||cost.signum()<=0)throw new BusinessException("交易金额无效");
  String chosen=source(source,expected);
  if(expected.equals(chosen)){
   if(cash.getAvailable().compareTo(cost)<0)throw new BusinessException("交易资产余额不足");
   cash.setAvailable(cash.getAvailable().subtract(cost));cash.setFrozen(cash.getFrozen().add(cost));assets.save(cash);checkpoint("reserve-cash");
   return new Reservation(ZERO,null);
  }
  TrialAccount a=account(user);maintainLocked(a);
  if(!a.isTrialEligible()||a.getAvailable().compareTo(cost)<0)throw new BusinessException(4601,"体验金已失效或余额不足");
  BigDecimal left=cost;Map<Long,BigDecimal> portions=new LinkedHashMap<>();
  List<TrialGrant> rows=grantRows(a);rows.sort(Comparator.comparing(TrialGrant::getExpiresAt,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(TrialGrant::getId));
  for(TrialGrant g:rows){if(!g.isActive()||g.getAvailable().signum()<=0)continue;
   BigDecimal take=g.getAvailable().min(left);g.setAvailable(g.getAvailable().subtract(take));g.setFrozen(g.getFrozen().add(take));
   grants.save(g);checkpoint("reserve-grant");portions.put(g.getId(),take);left=left.subtract(take);if(left.signum()==0)break;
  }
  if(left.signum()!=0)throw new BusinessException("体验金可用余额不足");
  a.setAvailable(a.getAvailable().subtract(cost));a.setFrozen(a.getFrozen().add(cost));record(a,ZERO,reason);
  return new Reservation(cost,encode(portions));
 }
 private String encode(Map<Long,BigDecimal> portions){try{return JSON.writeValueAsString(portions);}catch(Exception e){throw new IllegalStateException("资金分配序列化失败",e);}}
 private Map<Long,BigDecimal> decode(String value){
  if(value==null||value.isEmpty())return new LinkedHashMap<>();
  try{Map<String,BigDecimal> raw=JSON.readValue(value,new TypeReference<LinkedHashMap<String,BigDecimal>>(){});
   Map<Long,BigDecimal> out=new LinkedHashMap<>();for(Map.Entry<String,BigDecimal> e:raw.entrySet()){
    Long id=Long.valueOf(e.getKey());if(id<=0||e.getValue()==null||e.getValue().signum()<=0)throw new IllegalArgumentException();out.put(id,e.getValue());
   }return out;
  }catch(Exception e){throw new BusinessException("订单体验金资金分配记录无效");}
 }
 private void release(TrialAccount a,List<TrialGrant> rows,Map<Long,BigDecimal> portions){
  BigDecimal released=ZERO;
  for(Map.Entry<Long,BigDecimal> part:portions.entrySet()){
   TrialGrant g=rows.stream().filter(r->r.getId().equals(part.getKey())).findFirst().orElseThrow(()->new BusinessException("订单体验金批次不存在"));
   BigDecimal amount=part.getValue();if(g.getFrozen().compareTo(amount)<0)throw new BusinessException("体验金冻结金额不足");
   g.setFrozen(g.getFrozen().subtract(amount));a.setFrozen(a.getFrozen().subtract(amount));
   if(g.isActive()){g.setAvailable(g.getAvailable().add(amount));a.setAvailable(a.getAvailable().add(amount));}
   else {g.setExpired(g.getExpired().add(amount));a.setExpired(a.getExpired().add(amount));}
   grants.save(g);checkpoint("release-grant");released=released.add(amount);
  }
  if(released.signum()<0||a.getFrozen().signum()<0)throw new BusinessException("体验金冻结金额不足");
 }
 /** Legacy null-source orders use their recorded split, never a guessed new source. */
 public void settle(Long user,AssetAccount cash,BigDecimal reserved,BigDecimal trial,String allocations,String source,BigDecimal net,String reason){
  if(reserved==null||trial==null||net==null||reserved.signum()<0||trial.signum()<0||trial.compareTo(reserved)>0)throw new BusinessException("结算金额无效");
  boolean trialOnly="TRIAL".equals(source);boolean legacy=source==null;
  if(trialOnly&&trial.compareTo(reserved)!=0||!trialOnly&&!legacy&&trial.signum()!=0)throw new BusinessException("订单资金来源不一致");
  BigDecimal cashPrincipal=reserved.subtract(trial);
  if(cash.getFrozen().compareTo(cashPrincipal)<0)throw new BusinessException("资产冻结金额不足");
  if(cashPrincipal.signum()>0){cash.setFrozen(cash.getFrozen().subtract(cashPrincipal));cash.setAvailable(cash.getAvailable().add(cashPrincipal));}
  BigDecimal used=ZERO;
  if(trial.signum()>0){
   TrialAccount a=account(user);List<TrialGrant> rows=grantRows(a);
   // Settlement synchronously expires the grants it releases; a delayed maintenance job cannot revive principal.
   LocalDateTime instant=now();for(TrialGrant g:rows)if(g.isActive()&&g.getExpiresAt()!=null&&!instant.isBefore(g.getExpiresAt())){
    g.setActive(false);BigDecimal unused=g.getAvailable();g.setAvailable(ZERO);g.setExpired(g.getExpired().add(unused));
    a.setAvailable(a.getAvailable().subtract(unused));a.setExpired(a.getExpired().add(unused));grants.save(g);checkpoint("settlement-expiration-grant");
   }
   Map<Long,BigDecimal> portions=decode(allocations);
   if(legacy&&portions.isEmpty()){
    BigDecimal left=trial;
    for(TrialGrant g:rows){BigDecimal take=g.getFrozen().min(left);if(take.signum()>0)portions.put(g.getId(),take);left=left.subtract(take);if(left.signum()==0)break;}
    if(left.signum()!=0)throw new BusinessException("历史订单体验金冻结金额不足");
   }
   BigDecimal sum=portions.values().stream().reduce(ZERO,BigDecimal::add);
   if(sum.compareTo(trial)!=0)throw new BusinessException("订单体验金分配与冻结金额不符");
   release(a,rows,portions);
   if(net.signum()<0){BigDecimal left=net.negate();
    for(TrialGrant g:rows){if(!g.isActive()||g.getAvailable().signum()<=0)continue;
     BigDecimal take=g.getAvailable().min(left);g.setAvailable(g.getAvailable().subtract(take));g.setConsumed(g.getConsumed().add(take));grants.save(g);checkpoint("consume-grant");
     a.setAvailable(a.getAvailable().subtract(take));a.setConsumed(a.getConsumed().add(take));used=used.add(take);left=left.subtract(take);if(left.signum()==0)break;
    }
    if(trialOnly&&left.signum()>0)a.setUncoveredLoss(a.getUncoveredLoss().add(left));
   }
   if(net.signum()>0)a.setProfits(a.getProfits().add(net));
   a.setTrialEligible(rows.stream().anyMatch(g->g.isActive()&&(g.getAvailable().signum()>0||g.getFrozen().signum()>0)));
   record(a,used.negate(),reason);
  }
  // Existing contract/option profit rule: promotional wins credit real wallet.
  cash.setAvailable(cash.getAvailable().add(trialOnly?net.max(ZERO):net.add(legacy?used:ZERO)));
  // TRIAL losses never debit real. Historical mixed orders retain their original split.
  assets.save(cash);checkpoint("settlement-cash");
 }
 public void settle(Long user,AssetAccount cash,BigDecimal reserved,BigDecimal trial,BigDecimal net,String reason){settle(user,cash,reserved,trial,null,null,net,reason);}
 protected void checkpoint(String stage) { }
 public BigDecimal tradingBalance(Long user,BigDecimal real){return available(user).add(identity.canUseTradingFunds(user)?real:ZERO);}
 public BigDecimal selectedBalance(Long user,BigDecimal real,String source){return "TRIAL".equals(source)?available(user):real;}
 public boolean reservationExpired(String allocations){
  Map<Long,BigDecimal> portions=decode(allocations);
  for(Long id:new TreeSet<>(portions.keySet())){TrialGrant g=grants.findByTenantIdAndId(tenant(),id).orElseThrow(()->new BusinessException("订单体验金批次不存在"));
   if(entityManager!=null)entityManager.refresh(g,javax.persistence.LockModeType.PESSIMISTIC_READ);
   if(!g.isActive()||g.getExpiresAt()!=null&&!now().isBefore(g.getExpiresAt()))return true;
  }return false;
 }
 public Reservation resize(Long user,AssetAccount cash,BigDecimal oldCost,BigDecimal newCost,BigDecimal trial,String allocations,String source,String expected,String reason){
  if(newCost.signum()<0)throw new BusinessException("调整金额无效");
  String chosen=source==null?null:source(source,expected);BigDecimal delta=newCost.subtract(oldCost);
  if(chosen==null){
   // Only pre-upgrade mixed pending orders retain their recorded mixed funding policy.
   settle(user,cash,oldCost,trial,null,null,ZERO,reason);
   TrialAccount a=account(user);BigDecimal promotional=a.getAvailable().min(newCost),cashPart=newCost.subtract(promotional);
   Reservation bonus=promotional.signum()>0?reserve(user,cash,promotional,"TRIAL",expected,reason):new Reservation(ZERO,null);
   if(cashPart.signum()>0)reserve(user,cash,cashPart,expected,expected,reason);
   return bonus;
  }
  if(expected.equals(chosen)){
   if(delta.signum()>0&&cash.getAvailable().compareTo(delta)<0)throw new BusinessException("资产余额不足");
   cash.setAvailable(cash.getAvailable().subtract(delta));cash.setFrozen(cash.getFrozen().add(delta));assets.save(cash);checkpoint("resize-cash");return new Reservation(ZERO,null);
  }
  if(reservationExpired(allocations))throw new BusinessException(4601,"体验金挂单已到期");
  Map<Long,BigDecimal> portions=decode(allocations);
  if(delta.signum()>0){Reservation added=reserve(user,cash,delta,"TRIAL",expected,reason);decode(added.allocations).forEach((id,amount)->portions.merge(id,amount,BigDecimal::add));}
  else if(delta.signum()<0){BigDecimal left=delta.negate();Map<Long,BigDecimal> released=new LinkedHashMap<>();
   List<Long> ids=new ArrayList<>(portions.keySet());Collections.reverse(ids);
   for(Long id:ids){BigDecimal take=portions.get(id).min(left);if(take.signum()>0){released.put(id,take);BigDecimal remain=portions.get(id).subtract(take);if(remain.signum()==0)portions.remove(id);else portions.put(id,remain);left=left.subtract(take);}if(left.signum()==0)break;}
   if(left.signum()!=0)throw new BusinessException("订单体验金分配与调整金额不符");
   settle(user,cash,delta.negate(),delta.negate(),encode(released),"TRIAL",ZERO,reason);
  }
  return new Reservation(newCost,encode(portions));
 }
}
