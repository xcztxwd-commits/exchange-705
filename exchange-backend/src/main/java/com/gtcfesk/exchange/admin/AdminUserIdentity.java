package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import javax.persistence.criteria.*;
import java.util.*;
import java.util.stream.Collectors;

/** Administrator-only identity fields; never attach internal remarks to public user entities. */
@Service @RequiredArgsConstructor
public class AdminUserIdentity {
    private final UserAccountRepository users;
    private final ObjectMapper mapper;

    public static void put(Map<String,Object> row, UserAccount user) {
        row.put("userEmail", user == null ? null : user.getEmail());
        row.put("userRemark", user == null ? null : user.getRemark());
    }

    public List<Map<String,Object>> rows(List<?> source) {
        List<Map<String,Object>> rows = source.stream()
                .map(row -> (Map<String,Object>) mapper.convertValue(row, Map.class)).collect(Collectors.toList());
        Set<Long> ids = rows.stream().map(row -> (Number) row.get("userId")).filter(Objects::nonNull)
                .map(Number::longValue).collect(Collectors.toSet());
        Map<Long,UserAccount> owners = ids.isEmpty() ? Collections.emptyMap()
                : users.findAllByTenantIdAndIdIn(TenantContext.requireTenantId(), ids).stream()
                    .collect(Collectors.toMap(UserAccount::getId, user -> user));
        for (Map<String,Object> row : rows) {
            Number id = (Number) row.get("userId");
            put(row, id == null ? null : owners.get(id.longValue()));
        }
        return rows;
    }

    public Map<String,Object> row(Object source) { return rows(Collections.singletonList(source)).get(0); }

    public static String emailPattern(String email) {
        if (email == null || email.trim().isEmpty()) return null;
        String value = email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254) throw new IllegalArgumentException("邮箱搜索内容不能超过 254 字符");
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public static Predicate emailFilter(Expression<Long> userId, CriteriaQuery<?> query, CriteriaBuilder cb, String email) {
        String pattern = emailPattern(email);
        if (pattern == null) return cb.conjunction();
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<UserAccount> user = subquery.from(UserAccount.class);
        subquery.select(user.get("id")).where(cb.equal(user.get("tenantId"), TenantContext.requireTenantId()),
                cb.like(cb.lower(user.get("email")), pattern, '!'));
        return userId.in(subquery);
    }
}
