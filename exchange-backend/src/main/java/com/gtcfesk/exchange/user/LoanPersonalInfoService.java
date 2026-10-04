package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.KycRecord;
import com.gtcfesk.exchange.entity.LoanPersonalInfo;
import com.gtcfesk.exchange.repository.LoanPersonalInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LoanPersonalInfoService {
    private final LoanPersonalInfoRepository loanPersonalInfoRepository;
    private final KycIdentityService identityService;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;

    public KycRecord requireApprovedKyc(Long userId) {
        return identityService.latestRecord(userId).filter(record -> "APPROVED".equals(record.getStatus()))
                .orElseThrow(() -> new BusinessException("请先完成账户实名认证"));
    }

    private boolean validContact(LoanPersonalInfo info) {
        String phone = info.getPhone() == null ? "" : info.getPhone().trim();
        String address = info.getAddress() == null ? "" : info.getAddress().trim();
        int digits = phone.replaceAll("[^0-9]", "").length();
        return digits >= 6 && digits <= 15 && phone.length() <= 32 && phone.matches("\\+?[0-9 ()-]{5,32}") && phone.replaceAll("[^1-9]", "").length() > 0
                && !address.isEmpty() && address.length() <= 500
                && !java.util.Arrays.asList("无", "無", "none", "n/a", "-").contains(address.toLowerCase(java.util.Locale.ROOT))
                && info.getHandheldImage() != null && !info.getHandheldImage().trim().isEmpty() && info.getHandheldImage().length() <= 500;
    }

    private boolean matchesIdentity(LoanPersonalInfo info, KycRecord identity) {
        return identity != null && "APPROVED".equals(identity.getStatus())
                && Objects.equals(info.getRealName(), identity.getRealName())
                && Objects.equals(info.getIdNumber(), identity.getIdNumber());
    }

    public void validateForReview(LoanPersonalInfo info) {
        KycRecord identity = requireApprovedKyc(info.getUserId());
        if (!matchesIdentity(info, identity) || !validContact(info)) {
            throw new BusinessException("贷款资料与实名信息不一致或资料不完整，请重新提交贷款资料");
        }
    }

    public LoanPersonalInfo requireApprovedPersonalInfo(Long userId) {
        if (identityService.simulationExempt()) {
            LoanPersonalInfo info = new LoanPersonalInfo(); info.setUserId(userId);
            info.setRealName("Simulation user"); info.setIdNumber("SIMULATION-NOT-AN-ID");
            info.setPhone("SIMULATION"); info.setAddress("SIMULATION — NO REAL LOAN");
            info.setStatus("SIMULATION_EXEMPT"); return info;
        }
        LoanPersonalInfo info = loanPersonalInfoRepository.findByTenantIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId)
                .filter(record -> "APPROVED".equals(record.getStatus()))
                .orElseThrow(() -> new BusinessException("请先完成贷款资料审核"));
        validateForReview(info);
        return info;
    }

    /** Current reads for a money writer after its canonical user lock. UI reads remain non-locking. */
    public LoanPersonalInfo requireApprovedPersonalInfoForFunds(Long userId) {
        if (identityService.simulationExempt() || entityManager == null) return requireApprovedPersonalInfo(userId);
        Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        LoanPersonalInfo info = loanPersonalInfoRepository.lockByUserId(userId)
                .orElseThrow(() -> new BusinessException("请先完成贷款资料审核"));
        entityManager.refresh(info, javax.persistence.LockModeType.PESSIMISTIC_READ);
        java.util.List<KycRecord> identities = entityManager.createQuery("select k from KycRecord k where k.tenantId=:tenant and k.userId=:user order by k.createdAt desc,k.id desc", KycRecord.class)
                .setParameter("tenant", tenant).setParameter("user", userId)
                .setLockMode(javax.persistence.LockModeType.PESSIMISTIC_READ).setMaxResults(1).getResultList();
        KycRecord identity = identities.isEmpty() ? null : identities.get(0);
        if (identity != null) entityManager.refresh(identity, javax.persistence.LockModeType.PESSIMISTIC_READ);
        if (!"APPROVED".equals(info.getStatus()) || !matchesIdentity(info, identity) || !validContact(info))
            throw new BusinessException("贷款资料与实名信息不一致或资料不完整，请重新提交贷款资料");
        return info;
    }

    @Transactional
    public LoanPersonalInfo submitPersonalInfo(Long userId, String phone, String address, String handheldImage) {
        KycRecord identity = requireApprovedKyc(userId);
        LoanPersonalInfo info = loanPersonalInfoRepository.findByTenantIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId).orElseGet(LoanPersonalInfo::new);
        // Legacy identity columns are audit snapshots, never an independent identity source.
        boolean current = matchesIdentity(info, identity) && validContact(info);
        if (current && ("APPROVED".equals(info.getStatus()) || "PENDING".equals(info.getStatus()))) {
            throw new BusinessException("APPROVED".equals(info.getStatus()) ? "贷款资料已通过，无需重复提交" : "贷款资料正在审核中，请耐心等待");
        }
        info.setUserId(userId);
        info.setRealName(identity.getRealName());
        info.setIdNumber(identity.getIdNumber());
        info.setIdFrontImage(identity.getIdFrontImage());
        info.setIdBackImage(identity.getIdBackImage());
        info.setPhone(phone == null ? "" : phone.trim());
        info.setAddress(address == null ? "" : address.trim());
        if (handheldImage != null && !handheldImage.trim().isEmpty()) info.setHandheldImage(handheldImage.trim());
        if (!validContact(info)) throw new BusinessException("请填写有效联系电话、地址并上传手持证件照");
        info.setStatus("PENDING");
        info.setReviewRemark(null);
        info.setReviewedBy(null);
        info.setReviewedAt(null);
        return loanPersonalInfoRepository.save(info);
    }

    public Map<String, String> getKycInfoForLoan(Long userId) {
        Map<String, String> data = new HashMap<>();
        identityService.latestRecord(userId)
                .filter(record -> "APPROVED".equals(record.getStatus())).ifPresent(record -> {
                    data.put("realName", record.getRealName());
                    data.put("idNumber", record.getIdNumber());
                    data.put("idFrontImage", record.getIdFrontImage());
                    data.put("idBackImage", record.getIdBackImage());
                });
        return data;
    }

    public Map<String, Object> getPersonalInfoStatus(Long userId) {
        if (identityService.simulationExempt()) {
            Map<String,Object> result = new HashMap<>(); result.put("verified", false);
            result.put("exempt", true); result.put("status", "SIMULATION_EXEMPT");
            result.put("data", requireApprovedPersonalInfo(userId)); return result;
        }
        KycRecord identity = identityService.latestRecord(userId).orElse(null);
        LoanPersonalInfo info = loanPersonalInfoRepository.findByTenantIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId).orElse(null);
        boolean kycVerified = identity != null && "APPROVED".equals(identity.getStatus());
        boolean current = info != null && matchesIdentity(info, identity) && validContact(info);
        Map<String, Object> result = new HashMap<>();
        result.put("kycVerified", kycVerified);
        result.put("verified", current && "APPROVED".equals(info.getStatus()));
        result.put("status", info == null ? "NOT_SUBMITTED" : current ? info.getStatus() : "NEEDS_UPDATE");
        result.put("reviewRemark", info == null ? null : info.getReviewRemark());
        Map<String, Object> data = new HashMap<>();
        if (kycVerified) {
            data.put("realName", identity.getRealName());
            data.put("idNumber", identity.getIdNumber());
            data.put("idFrontImage", identity.getIdFrontImage());
            data.put("idBackImage", identity.getIdBackImage());
        }
        if (info != null) {
            data.put("phone", info.getPhone());
            data.put("address", info.getAddress());
            data.put("handheldImage", info.getHandheldImage());
            data.put("createdAt", info.getCreatedAt());
            data.put("reviewedAt", info.getReviewedAt());
        }
        result.put("data", data);
        return result;
    }
}
