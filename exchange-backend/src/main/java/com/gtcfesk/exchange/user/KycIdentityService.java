package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.KycRequiredException;
import com.gtcfesk.exchange.entity.KycRecord;
import com.gtcfesk.exchange.repository.KycRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Optional;

/** The latest KYC submission is authoritative; only APPROVED permits trading, never cached flags. */
@Service
@RequiredArgsConstructor
public class KycIdentityService {
    private final KycRecordRepository records;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationEnvironment simulation;
    public boolean simulationExempt() { return simulation != null && simulation.enabled(); }
    public static final String TRADE_KYC_KEY = "trade.kyc.required";
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.admin.SystemConfigService configs;
    // Existing installations remain protected until an administrator explicitly disables the gate.
    public boolean tradingKycRequired() { return configs == null || !"false".equals(configs.getConfigValue(TRADE_KYC_KEY)); }
    public boolean canUseTradingFunds(Long userId) { return simulationExempt() || !tradingKycRequired() || isApproved(userId); }
    public void requireTradingApproved(Long userId) { if (tradingKycRequired()) requireApproved(userId); }
    public Optional<KycRecord> latestRecord(Long userId) {
        return records.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId);
    }
    private void requireCallerTransaction(){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Current KYC authority requires the caller transaction");
    }
    /** KYC writers and funding readers share user -> KYC order, including a first submission. */
    private void lockCurrentUser(Long user){
        requireCallerTransaction();
        org.hibernate.query.NativeQuery<?> query=em.createNativeQuery("SELECT * FROM user_account WHERE tenant_id=?1 AND id=?2 FOR UPDATE").unwrap(org.hibernate.query.NativeQuery.class);
        query.addEntity("locked",com.gtcfesk.exchange.entity.UserAccount.class,org.hibernate.LockMode.NONE);
        query.setParameter(1,com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());query.setParameter(2,user);
        java.util.List<?> rows=query.getResultList();if(rows.size()!=1)throw new com.gtcfesk.exchange.common.BusinessException("用户不存在");
        em.refresh(rows.get(0),javax.persistence.LockModeType.PESSIMISTIC_WRITE);
    }
    private KycRecord currentLatest(Long user){
        org.hibernate.query.NativeQuery<?> query=em.createNativeQuery("SELECT * FROM kyc_record WHERE tenant_id=?1 AND user_id=?2 ORDER BY created_at DESC,id DESC LIMIT 1 FOR UPDATE").unwrap(org.hibernate.query.NativeQuery.class);
        query.addEntity("locked",KycRecord.class,org.hibernate.LockMode.NONE);query.setParameter(1,com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());query.setParameter(2,user);
        java.util.List<?> rows=query.getResultList();if(rows.isEmpty())return null;
        KycRecord row=(KycRecord)rows.get(0);em.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_WRITE);return row;
    }
    public void requireCurrentTradingApproved(Long user){
        lockCurrentUser(user);
        if(simulationExempt())return;
        if("false".equals(configs.getCurrentConfigValue(TRADE_KYC_KEY)))return;
        KycRecord row=currentLatest(user);
        if(row==null||!"APPROVED".equals(row.getStatus()))throw new KycRequiredException(row==null?"NOT_VERIFIED":row.getStatus());
    }
    /** Upload/field validation remains outside this short final-save transaction. */
    public KycRecord submitCurrent(KycRecord prepared){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("KYC submission preparation cannot join an outer transaction");
        org.springframework.transaction.support.TransactionTemplate transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        // First KYC rows have no child anchor: RC avoids a missing-row gap across unrelated users.
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> submitLocked(prepared));
    }
    private KycRecord submitLocked(KycRecord prepared){
        if(prepared==null||prepared.getUserId()==null)throw new com.gtcfesk.exchange.common.BusinessException("申请无效");
        lockCurrentUser(prepared.getUserId());KycRecord latest=currentLatest(prepared.getUserId());
        if(latest!=null&&"PENDING".equals(latest.getStatus()))throw new com.gtcfesk.exchange.common.BusinessException("您已有审核中的申请，请等待审核");
        if(latest!=null&&"APPROVED".equals(latest.getStatus()))throw new com.gtcfesk.exchange.common.BusinessException("您已完成实名认证");
        prepared.setStatus("PENDING");return records.save(prepared);
    }
    public KycRecord currentForReview(Long id){
        requireCallerTransaction();long tenant=com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        java.util.List<Long> owners=em.createQuery("select k.userId from KycRecord k where k.tenantId=:tenant and k.id=:id",Long.class)
                .setParameter("tenant",tenant).setParameter("id",id).getResultList();
        if(owners.isEmpty())throw new com.gtcfesk.exchange.common.BusinessException("申请不存在");Long owner=owners.get(0);lockCurrentUser(owner);
        org.hibernate.query.NativeQuery<?> query=em.createNativeQuery("SELECT * FROM kyc_record WHERE tenant_id=?1 AND id=?2 FOR UPDATE").unwrap(org.hibernate.query.NativeQuery.class);
        query.addEntity("locked",KycRecord.class,org.hibernate.LockMode.NONE);query.setParameter(1,tenant);query.setParameter(2,id);
        java.util.List<?> rows=query.getResultList();if(rows.size()!=1)throw new com.gtcfesk.exchange.common.BusinessException("申请不存在");
        KycRecord row=(KycRecord)rows.get(0);em.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(!owner.equals(row.getUserId()))throw new com.gtcfesk.exchange.common.BusinessException("申请归属已变化，请重试");return row;
    }
    public boolean isApproved(Long userId) {
        return latestRecord(userId).map(record -> "APPROVED".equals(record.getStatus())).orElse(false);
    }
    public KycRecord requireApproved(Long userId) {
        if (simulationExempt()) {
            KycRecord simulated = new KycRecord(); simulated.setUserId(userId);
            simulated.setRealName("Simulation user"); simulated.setIdNumber("SIMULATION-NOT-AN-ID");
            simulated.setStatus("SIMULATION_EXEMPT"); return simulated; // Never persisted as an approved identity.
        }
        KycRecord record = latestRecord(userId).orElse(null);
        if (record == null || !"APPROVED".equals(record.getStatus())) {
            throw new KycRequiredException(record == null ? "NOT_VERIFIED" : record.getStatus());
        }
        return record;
    }
}
