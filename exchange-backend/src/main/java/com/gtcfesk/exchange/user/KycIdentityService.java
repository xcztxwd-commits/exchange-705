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
