package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.*;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class UserActivityService {
    public static final Set<String> PAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "home","trade","option","contract","assets","financial","support","orders","profile","deposit",
            "withdraw","wallet","verification","transfer","security","inbox","announcement","loan","search","invite","settings","unknown")));
    private final UserAccountRepository users;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.activity.ActivityService activities;
    public void touch(Long id) {
        LocalDateTime now = LocalDateTime.now(); users.touchActive(id, now, now.minusSeconds(30));
    }
    public void report(Long id, String page, String device, long sequence) {
        if (!PAGES.contains(page) || !Arrays.asList("PC", "MOBILE").contains(device) || sequence < 0
                || sequence > System.currentTimeMillis() + 300000) throw new IllegalArgumentException("页面上报参数无效");
        LocalDateTime now = LocalDateTime.now();
        int changed=users.reportPage(id, page, device, sequence, now, now.minusSeconds(1));
        touch(id);
        if(changed>0&&activities!=null){
            String activityPage=Arrays.asList("contract","option").contains(page)?"trade":page;
            String position=Arrays.asList("home","trade","profile","support").contains(activityPage)?
                ("home".equals(activityPage)?"AUTH_HOME":"trade".equals(activityPage)?"AUTH_TRADE":"profile".equals(activityPage)?"AUTH_PROFILE":"SUPPORT"):null;
            if(position!=null)activities.trigger(id,"PAGE_"+activityPage.toUpperCase(Locale.ROOT),position);
        }
    }
    private Specification<UserAccount> online(LocalDateTime asOf, Long agent) {
        return (root, query, b) -> b.and(b.greaterThanOrEqualTo(root.get("lastActivityAt"), asOf.minusMinutes(5)),
                b.isNotNull(root.get("currentToken")), root.get("status").in("normal", "active"),
                agent == null ? b.conjunction() : b.equal(root.get("parentUserId"), agent));
    }
    public long count(Long agent) { return users.countByTenantId(TenantContext.requireTenantId(), online(LocalDateTime.now(), agent)); }
    public Map<String,Object> list(Long agent, int page, int size) { return list(agent,page,size,null); }
    public Map<String,Object> list(Long agent, int page, int size, String userEmail) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数无效");
        LocalDateTime asOf = LocalDateTime.now();
        String pattern = com.gtcfesk.exchange.admin.AdminUserIdentity.emailPattern(userEmail);
        Specification<UserAccount> filter = online(asOf, agent);
        if (pattern != null) filter = filter.and((root, query, cb) -> cb.like(cb.lower(root.get("email")), pattern, '!'));
        Page<UserAccount> rows = users.findAllByTenantId(TenantContext.requireTenantId(), filter, PageRequest.of(page, size, Sort.by("id")));
        List<Map<String,Object>> items = new ArrayList<>();
        for (UserAccount user : rows) {
            Map<String,Object> row = new LinkedHashMap<>(); row.put("id", user.getId()); row.put("account", user.getEmail()); com.gtcfesk.exchange.admin.AdminUserIdentity.put(row, user);
            row.put("lastLoginIp", user.getLastLoginIp()); row.put("lastLoginRegion", user.getLastLoginRegion());
            row.put("lastPageCode", user.getLastPageCode() == null ? "unknown" : user.getLastPageCode());
            row.put("lastPageSeenAt", user.getLastPageSeenAt()); row.put("lastActiveAt", user.getLastActivityAt()); row.put("deviceType", user.getLastDeviceType());
            items.add(row);
        }
        Map<String,Object> data = new LinkedHashMap<>(); data.put("items", items); data.put("total", rows.getTotalElements()); data.put("asOf", asOf);
        data.put("activityThrottleSeconds", 30); data.put("pageSource", "客户端上报，最近访问");
        Map<String,Object> response = new LinkedHashMap<>(); response.put("success", true); response.put("data", data); return response;
    }
}
