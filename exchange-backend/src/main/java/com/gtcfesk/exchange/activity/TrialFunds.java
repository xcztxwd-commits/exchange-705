package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.user.KycIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

/** Separate promotional principal; never exposed as a transferable asset account. */
@Service @RequiredArgsConstructor
public class TrialFunds {
 private final TrialAccountRepository trials;
 private final TrialLedgerRepository ledger;
 private final UserAccountRepository users;
 private final AssetAccountRepository assets;
 private final KycIdentityService identity;
 public BigDecimal available(Long user){return trials.findById(user).map(TrialAccount::getAvailable).orElse(BigDecimal.ZERO);}
 public boolean canTrade(Long user){return identity.canUseTradingFunds(user)||available(user).signum()>0;}
 public void requireTrade(Long user){if(!canTrade(user))identity.requireApproved(user);}
 // Parent-user lock serializes first-account creation, claims, reservations and settlement.
 public void lock(Long user){users.lockById(user).orElseThrow(()->new BusinessException("用户不存在"));assets.lockByUserId(user);}
 private TrialAccount account(Long user){return trials.lock(user).orElseGet(()->{TrialAccount a=new TrialAccount();a.setUserId(user);return trials.save(a);});}
 public TrialAccount snapshot(Long user){return trials.findById(user).orElseGet(()->{TrialAccount a=new TrialAccount();a.setUserId(user);return a;});}
 public void record(TrialAccount a,BigDecimal delta,String reason){trials.save(a);TrialLedger l=new TrialLedger();l.setUserId(a.getUserId());l.setAvailable(a.getAvailable());l.setFrozen(a.getFrozen());l.setDelta(delta);l.setReason(reason);ledger.save(l);}
 @Transactional public TrialAccount grant(Long user,BigDecimal amount,Long delivery){
  lock(user);TrialAccount a=account(user);a.setAvailable(a.getAvailable().add(amount));a.setGranted(a.getGranted().add(amount));record(a,amount,"CLAIM:"+delivery);return a;
 }
 /** Called inside order transaction, before real funds are read or changed. */
 public BigDecimal reserve(Long user,AssetAccount real,BigDecimal cost,String reason){
  TrialAccount a=account(user);BigDecimal trial=a.getAvailable().min(cost),cash=cost.subtract(trial);
  if(!identity.canUseTradingFunds(user) && cash.signum()>0)identity.requireApproved(user);
  if(real.getAvailable().compareTo(cash)<0)throw new BusinessException("交易资产余额不足");
  a.setAvailable(a.getAvailable().subtract(trial));a.setFrozen(a.getFrozen().add(trial));
  real.setAvailable(real.getAvailable().subtract(cash));real.setFrozen(real.getFrozen().add(cash));assets.save(real);
  if(trial.signum()>0)record(a,BigDecimal.ZERO,reason);return trial;
 }
 /** Release principal, charge net losses/fees to trial first, credit net profits only to real account. */
 public void settle(Long user,AssetAccount real,BigDecimal reserved,BigDecimal trial,BigDecimal net,String reason){
  TrialAccount a=account(user);BigDecimal cash=reserved.subtract(trial);
  if(trial.signum()<0||cash.signum()<0||a.getFrozen().compareTo(trial)<0||real.getFrozen().compareTo(cash)<0)throw new BusinessException("冻结金额不足");
  a.setFrozen(a.getFrozen().subtract(trial));a.setAvailable(a.getAvailable().add(trial));
  real.setFrozen(real.getFrozen().subtract(cash));real.setAvailable(real.getAvailable().add(cash));
  BigDecimal used=net.signum()<0?a.getAvailable().min(net.negate()):BigDecimal.ZERO;
  a.setAvailable(a.getAvailable().subtract(used));a.setConsumed(a.getConsumed().add(used));
  real.setAvailable(real.getAvailable().add(net).add(used));
  if(net.signum()>0 && trial.signum()>0)a.setProfits(a.getProfits().add(net));
  assets.save(real);if(trial.signum()>0||used.signum()>0)record(a,used.negate(),reason);
 }
 public BigDecimal tradingBalance(Long user,BigDecimal real){return available(user).add(identity.canUseTradingFunds(user)?real:BigDecimal.ZERO);}
}
