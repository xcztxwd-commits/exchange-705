package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/** Resolve administrator-visible identity labels from the real owner while retaining demo financial data. */
@Service @RequiredArgsConstructor
public class AdminSimulationIdentity {
    private final UserAccountRepository users;
    private final KycRecordRepository identities;
    private final SystemConfigService configs;
    private final ObjectMapper mapper;
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
            "email", "nickname", "remark", "avatarUrl", "countryCode", "phone", "userType", "status",
            "parentUserId", "parentUserEmail", "inviteCode", "myInviteCode", "annualIncome", "annualIncomeCurrency",
            "createdAt", "lastLoginIp", "lastLoginRegion", "lastLoginAt", "lastLoginDomain", "lastActivityAt",
            "realName", "kycLevel", "kycStatus", "userTypeLabel", "subordinateCount"));

    public byte[] canonicalize(byte[] body) throws IOException {
        Object result = mapper.readerFor(Object.class).with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(body);
        List<Map<String,Object>> rows = new ArrayList<>(); collect(result, rows);
        Set<Long> ids = rows.stream().map(this::owner).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return body;
        Long tenant = TenantContext.requireTenantId();
        Map<Long,UserAccount> owners = users.findAllByTenantIdAndIdIn(tenant, ids).stream()
                .collect(Collectors.toMap(UserAccount::getId, user -> user));
        Set<Long> parents = owners.values().stream().map(UserAccount::getParentUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long,String> parentEmails = new HashMap<>();
        if (!parents.isEmpty()) for (UserAccount parent : users.findAllByTenantIdAndIdIn(tenant, parents))
            parentEmails.put(parent.getId(), parent.getEmail());
        Map<Long,Map<String,Object>> profiles = new HashMap<>();
        String whitelist = configs.getConfigValue("domain.whitelist");
        for (UserAccount user : owners.values()) {
            Map<String,Object> profile = mapper.convertValue(user, Map.class);
            profile.put("parentUserEmail", parentEmails.get(user.getParentUserId()));
            profile.put("subordinateCount", users.findByTenantIdAndParentUserId(tenant, user.getId()).size());
            profile.put("realName", identities.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(tenant, user.getId())
                    .map(record -> record.getRealName()).orElse(null));
            profile.put("userTypeLabel", user.getLastLoginDomain() != null && whitelist != null && !whitelist.isEmpty()
                    && com.gtcfesk.exchange.utils.DomainUtils.isDomainInWhitelist(user.getLastLoginDomain(), whitelist) ? "真人" : "假人");
            profiles.put(user.getId(), profile);
        }
        for (Map<String,Object> row : rows) {
            Map<String,Object> profile = profiles.get(owner(row));
            if (profile == null) continue;
            if (row.containsKey("email")) for (String field : FIELDS)
                if (row.containsKey(field)) row.put(field, profile.get(field));
            if (row.containsKey("userEmail")) row.put("userEmail", profile.get("email"));
            if (row.containsKey("userRemark")) row.put("userRemark", profile.get("remark"));
        }
        return mapper.writeValueAsBytes(result);
    }

    private void collect(Object value, List<Map<String,Object>> rows) {
        if (value instanceof List) for (Object item : (List<?>) value) collect(item, rows);
        if (value instanceof Map) {
            Map<String,Object> row = (Map<String,Object>) value;
            if (row.containsKey("userEmail") || row.containsKey("userRemark")
                    || row.containsKey("email") && (row.containsKey("nickname") || row.containsKey("userId"))) rows.add(row);
            for (Object item : new ArrayList<>(row.values())) collect(item, rows);
        }
    }

    private Long owner(Map<String,Object> row) {
        Object id = row.containsKey("nickname") && row.containsKey("email") && row.containsKey("id") ? row.get("id") : row.get("userId");
        return id instanceof Number ? ((Number) id).longValue() : null;
    }
}
