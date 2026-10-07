package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.WithdrawRecord;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.UserBankCardRepository;
import com.gtcfesk.exchange.repository.UserDigitalAddressRepository;
import com.gtcfesk.exchange.repository.WithdrawRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/withdraw")
@RequiredArgsConstructor
public class WithdrawController {
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.repository.UserAccountRepository users;
    @org.springframework.beans.factory.annotation.Autowired private KycIdentityService identityService;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService audit;
    
    private final WithdrawRecordRepository withdrawRecordRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final UserDigitalAddressRepository userDigitalAddressRepository;
    private final UserBankCardRepository userBankCardRepository;

    private final FiatCurrencyService fiatCurrencyService;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager em;

    /**
     * 提交提现申请
     */
    @PostMapping("/submit")
    @Transactional
    public ResponseEntity<?> submitWithdraw(Authentication auth, @RequestBody Map<String, Object> req) {
        tenantPolicy.requireNewBusiness("withdraw");
        try {
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录");
                return ResponseEntity.status(401).body(resp);
            }
            
            Long userId = Long.parseLong(auth.getName());
            if (identityService != null) identityService.requireApproved(userId);
            String type = (String) req.get("type"); // digital 或 bank
            if (!"digital".equals(type) && !"bank".equals(type)) {
                throw new com.gtcfesk.exchange.common.BusinessException("提现类型无效");
            }
            String accountType = req.get("accountType") == null ? "FUND" : req.get("accountType").toString().trim().toUpperCase(java.util.Locale.ROOT);
            if (!"FUND".equals(accountType) && !"CONTRACT".equals(accountType) && !"OPTION".equals(accountType)) {
                throw new com.gtcfesk.exchange.common.BusinessException("账户类型无效");
            }
            String network = (String) req.get("network"); // 如 USDT-TRC20, USD
            BigDecimal originalAmount = new BigDecimal(req.get("amount").toString());
            com.gtcfesk.exchange.common.TradeValidation.positive(originalAmount, "提现金额");
            String currency = "bank".equals(type) ? fiatCurrencyService.currency((String) req.get("currency")) : "USD";
            String address = (String) req.get("address");
            String remark = (String) req.get("remark");
            
            // 验证参数
            if (type == null || network == null || originalAmount == null || address == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "参数不完整");
                return ResponseEntity.badRequest().body(resp);
            }
            
            if (originalAmount.compareTo(BigDecimal.ZERO) <= 0) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "提现金额必须大于0");
                return ResponseEntity.badRequest().body(resp);
            }
            
            String requestKey=com.gtcfesk.exchange.common.OrderRequest.required(req.get("requestId"));
            String requestHash=com.gtcfesk.exchange.common.OrderRequest.hash("withdraw",type,network,originalAmount,currency,address,remark);
            // Preserve retry compatibility for FUND receipts created before wallet selection.
            if (!"FUND".equals(accountType)) requestHash=com.gtcfesk.exchange.common.OrderRequest.hash(requestHash,accountType);
            boolean replayHint=withdrawRecordRepository.findReplayId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId,requestKey).isPresent();
            BigDecimal rate = replayHint ? null : "bank".equals(type) ? fiatCurrencyService.rate(currency) : BigDecimal.ONE;
            BigDecimal amount = rate == null ? null : fiatCurrencyService.toUsd(originalAmount, rate);
            if(em!=null){em.flush();com.gtcfesk.exchange.entity.UserAccount loaded=users.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId).orElseThrow(()->new com.gtcfesk.exchange.common.BusinessException("用户不存在"));em.refresh(loaded,javax.persistence.LockModeType.PESSIMISTIC_WRITE);}
            com.gtcfesk.exchange.entity.UserAccount customer=users.lockById(userId).orElseThrow(()->new com.gtcfesk.exchange.common.BusinessException("用户不存在"));
            if(em!=null){em.flush();em.refresh(customer,javax.persistence.LockModeType.PESSIMISTIC_WRITE);}
            if(em!=null){em.flush();java.util.List<AssetAccount> loaded=new java.util.ArrayList<>(assetAccountRepository.findByTenantIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId));loaded.sort(java.util.Comparator.comparing(AssetAccount::getCoin).thenComparing(AssetAccount::getId));for(AssetAccount row:loaded){em.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_WRITE);com.gtcfesk.exchange.tenant.TenantContext.require(row.getTenantId());if(!userId.equals(row.getUserId()))throw new com.gtcfesk.exchange.common.BusinessException("账户归属已变更，请重试");}}
            List<AssetAccount> lockedAccounts=assetAccountRepository.lockByUserId(userId);
            if(em!=null){em.flush();for(AssetAccount account:lockedAccounts)em.refresh(account,javax.persistence.LockModeType.PESSIMISTIC_WRITE);}
            if(em!=null){em.flush();withdrawRecordRepository.findReplayId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId,requestKey).flatMap(id->withdrawRecordRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),id)).ifPresent(row->em.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_READ));}
            WithdrawRecord previous=withdrawRecordRepository.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId,requestKey).orElse(null);
            if(previous!=null) {
                if(em!=null)em.refresh(previous,javax.persistence.LockModeType.PESSIMISTIC_READ);
                com.gtcfesk.exchange.common.OrderRequest.same(previous.getRequestHash(),requestHash);
                Map<String,Object> replay=new HashMap<>();replay.put("success",true);replay.put("orderId",previous.getId());replay.put("message","提现申请已提交，请核对原记录");return ResponseEntity.ok(replay);
            }
            if(amount==null)throw new com.gtcfesk.exchange.common.BusinessException("原提现收据已变更，请使用原请求编号重试");
            tenantPolicy.requireNewBusiness("withdraw");
            requireIdentityCurrent(userId);
            // 使用所选钱包的可用余额，冻结资金不能出金。
            AssetAccount sourceAccount = lockedAccounts.stream().filter(a -> accountType.equals(a.getCoin())).findFirst().orElse(null);
            
            if (sourceAccount == null || sourceAccount.getAvailable() == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "余额不足");
                return ResponseEntity.badRequest().body(resp);
            }
            
            BigDecimal available = sourceAccount.getAvailable();
            BigDecimal fee = calculateFee(type, network, amount); // 计算手续费
            BigDecimal totalNeeded = amount.add(fee); // 提现金额 + 手续费
            
            // 检查余额是否足够
            if (available.compareTo(totalNeeded) < 0) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "余额不足，需要 " + totalNeeded + "，当前可用余额 " + available);
                return ResponseEntity.badRequest().body(resp);
            }
            
            // 验证地址/账户是否属于当前用户
            if ("digital".equals(type)) {
                boolean addressExists = boundAddress(userId,type,address,network);
                if (!addressExists) {
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("success", false);
                    resp.put("message", "提币地址未绑定或不属于当前用户");
                    return ResponseEntity.badRequest().body(resp);
                }
            } else if ("bank".equals(type)) {
                boolean accountExists = boundAddress(userId,type,address,network);
                if (!accountExists) {
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("success", false);
                    resp.put("message", "收款账户未绑定或不属于当前用户");
                    return ResponseEntity.badRequest().body(resp);
                }
            }
            
            // 冻结金额（提现金额 + 手续费）
            sourceAccount.setAvailable(available.subtract(totalNeeded));
            sourceAccount.setFrozen(sourceAccount.getFrozen().add(totalNeeded));
            assetAccountRepository.save(sourceAccount);checkpoint("freeze-account");
            
            // 创建提现记录
            WithdrawRecord record = new WithdrawRecord();
            record.setRequestKey(requestKey);record.setRequestHash(requestHash);
            record.setUserId(userId);
            record.setType(type);
            record.setAccountType(accountType);
            record.setNetwork(network);
            record.setAmount(amount);
            record.setFee(fee);
            record.setActualAmount(amount); // 银行卡以 USD 结算
            if ("bank".equals(type)) {
                record.setCurrency(currency);
                record.setOriginalAmount(originalAmount);
                record.setExchangeRate(rate);
            }
            record.setAddress(address);
            record.setRemark(remark);
            record.setStatus("PENDING");
            
            if (identityService != null && identityService.simulationExempt()) {
                sourceAccount.setFrozen(sourceAccount.getFrozen().subtract(totalNeeded));
                assetAccountRepository.save(sourceAccount);checkpoint("simulation-account");
                record.setStatus("COMPLETED"); record.setReviewRemark("SIMULATION ONLY — no external payment");
                record.setReviewedAt(java.time.LocalDateTime.now());
            }
            withdrawRecordRepository.save(record);checkpoint("withdrawal-order");
            auditSuccess("WITHDRAW_SUBMIT",String.valueOf(record.getId()),"userId="+userId+"; accountType="+accountType+"; amount="+amount+"; fee="+fee+"; availableBefore="+available+"; availableAfter="+sourceAccount.getAvailable()+"; frozenAfter="+sourceAccount.getFrozen()+"; status="+record.getStatus(),null);checkpoint("withdrawal-audit");
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("orderId",record.getId());
            resp.put("message", identityService != null && identityService.simulationExempt() ? "模拟提现已完成，仅扣减虚拟资金，不会真实出款" : "提现申请已提交，等待审核");
            return ResponseEntity.ok(resp);
        } catch (com.gtcfesk.exchange.common.KycRequiredException e) {
            throw e;
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "提交失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    private void auditSuccess(String action,String object,String detail,String reason) {
        if(com.gtcfesk.exchange.control.ControlIdentity.current()!=null)audit.recordCurrent(action,object,detail,reason);
        else audit.record(null,com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),null,action,object,"SUCCESS",detail,reason);
    }
    private void requireIdentityCurrent(Long userId) {
        if(identityService==null||identityService.simulationExempt())return;
        if(em==null){identityService.requireApproved(userId);return;}
        java.util.List<com.gtcfesk.exchange.entity.KycRecord> rows=em.createQuery("select k from KycRecord k where k.tenantId=:tenant and k.userId=:user order by k.createdAt desc,k.id desc",com.gtcfesk.exchange.entity.KycRecord.class)
                .setParameter("tenant",com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()).setParameter("user",userId)
                .setLockMode(javax.persistence.LockModeType.PESSIMISTIC_READ).setMaxResults(1).getResultList();
        com.gtcfesk.exchange.entity.KycRecord record=rows.isEmpty()?null:rows.get(0);
        if(record!=null)em.refresh(record,javax.persistence.LockModeType.PESSIMISTIC_READ);
        if(record==null||!"APPROVED".equals(record.getStatus()))throw new com.gtcfesk.exchange.common.KycRequiredException(record==null?"NOT_VERIFIED":record.getStatus());
    }
    private boolean boundAddress(Long userId,String type,String address,String network) {
        Long tenant=com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        if("digital".equals(type)){
            List<com.gtcfesk.exchange.entity.UserDigitalAddress> rows=em==null?userDigitalAddressRepository.findByTenantIdAndUserId(tenant,userId):
                    em.createQuery("select a from UserDigitalAddress a where a.tenantId=:tenant and a.userId=:user order by a.id",com.gtcfesk.exchange.entity.UserDigitalAddress.class)
                            .setParameter("tenant",tenant).setParameter("user",userId).setLockMode(javax.persistence.LockModeType.PESSIMISTIC_READ).getResultList();
            if(em!=null)for(com.gtcfesk.exchange.entity.UserDigitalAddress row:rows)em.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_READ);
            return rows.stream().anyMatch(row->address.equals(row.getAddress())&&network.equals(row.getNetwork()));
        }
        List<com.gtcfesk.exchange.entity.UserBankCard> rows=em==null?userBankCardRepository.findByTenantIdAndUserId(tenant,userId):
                em.createQuery("select a from UserBankCard a where a.tenantId=:tenant and a.userId=:user order by a.id",com.gtcfesk.exchange.entity.UserBankCard.class)
                        .setParameter("tenant",tenant).setParameter("user",userId).setLockMode(javax.persistence.LockModeType.PESSIMISTIC_READ).getResultList();
        if(em!=null)for(com.gtcfesk.exchange.entity.UserBankCard row:rows)em.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_READ);
        return rows.stream().anyMatch(row->address.equals(row.getRecipientAccount()));
    }
    /** Isolated rollback tests may fail after each actual financial write. */
    protected void checkpoint(String stage) { }

    /**
     * 获取提现记录
     */
    @GetMapping("/records")
    public ResponseEntity<?> getRecords(Authentication auth) {
        try {
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录");
                return ResponseEntity.status(401).body(resp);
            }
            
            Long userId = Long.parseLong(auth.getName());
            List<WithdrawRecord> records = withdrawRecordRepository.findByTenantIdAndUserIdOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", records);
            return ResponseEntity.ok(resp);
        } catch (com.gtcfesk.exchange.common.KycRequiredException e) {
            throw e;
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 计算手续费
     */
    private BigDecimal calculateFee(String type, String network, BigDecimal amount) {
        // 简化处理：数字货币手续费为0，银行卡手续费为0
        // 后续可以从系统配置中读取手续费率
        return BigDecimal.ZERO;
    }

    /**
     * 计算 USD 结算金额和手续费
     */
    @PostMapping("/calculate")
    public ResponseEntity<?> calculateAmount(@RequestBody Map<String, Object> req) {
        try {
            String type = (String) req.get("type");
            if (!"digital".equals(type) && !"bank".equals(type)) {
                throw new com.gtcfesk.exchange.common.BusinessException("提现类型无效");
            }
            String network = (String) req.get("network");
            BigDecimal originalAmount = new BigDecimal(req.get("amount").toString());
            com.gtcfesk.exchange.common.TradeValidation.positive(originalAmount, "提现金额");
            String currency = "bank".equals(type) ? fiatCurrencyService.currency((String) req.get("currency")) : "USD";
            BigDecimal rate = "bank".equals(type) ? fiatCurrencyService.rate(currency) : BigDecimal.ONE;
            BigDecimal amount = fiatCurrencyService.toUsd(originalAmount, rate);

            BigDecimal fee = calculateFee(type, network, amount);
            BigDecimal actualAmount = amount;

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("fee", fee);
            resp.put("actualAmount", actualAmount);
            resp.put("amount", amount);
            resp.put("totalNeeded", amount.add(fee));
            resp.put("settlementCurrency", "USD");
            return ResponseEntity.ok(resp);
        } catch (com.gtcfesk.exchange.common.KycRequiredException e) {
            throw e;
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "计算失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

}
