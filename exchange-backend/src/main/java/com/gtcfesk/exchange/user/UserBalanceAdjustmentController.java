package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.BalanceAdjustment;
import com.gtcfesk.exchange.repository.BalanceAdjustmentRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Customer-safe projection of immutable adjustment receipts; operator and internal reasons stay private. */
@RestController
@RequestMapping("/api/user/balance-adjustments")
@RequiredArgsConstructor
public class UserBalanceAdjustmentController {
    private final BalanceAdjustmentRepository adjustments;
    private static final Pattern CHANGE=Pattern.compile("(?:^|;)(FUND|CONTRACT|OPTION):(-?\\d+(?:\\.\\d+)?)->(-?\\d+(?:\\.\\d+)?);delta=(-?\\d+(?:\\.\\d+)?)");

    @GetMapping
    public ResponseEntity<?> get(Authentication auth, @RequestParam(defaultValue="0") int page,
                                 @RequestParam(defaultValue="20") int size) {
        if (auth==null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))
                || !auth.getName().matches("[0-9]{1,19}")) throw new AccessDeniedException("请先登录用户账户");
        if (page<0 || size<1 || size>100) throw new IllegalArgumentException("分页参数无效");
        Long userId=Long.valueOf(auth.getName());
        Page<BalanceAdjustment> result=adjustments.findAllByTenantId(TenantContext.requireTenantId(),
                (root,query,builder) -> builder.and(builder.equal(root.get("userId"),userId),builder.like(root.get("changes"),"%delta=%")),
                PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt","id")));
        List<Map<String,Object>> list=new ArrayList<>();
        for (BalanceAdjustment record:result.getContent()) list.addAll(publicChanges(record));
        Map<String,Object> response=new LinkedHashMap<>(); response.put("success",true); response.put("list",list);
        response.put("hasMore",result.hasNext()); response.put("page",page);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    static List<Map<String,Object>> publicChanges(BalanceAdjustment record) {
        List<Map<String,Object>> result=new ArrayList<>();
        Matcher matcher=CHANGE.matcher(record.getChanges()==null?"":record.getChanges());
        while (matcher.find()) {
            BigDecimal amount=new BigDecimal(matcher.group(4));
            if (amount.signum()==0) continue;
            Map<String,Object> row=new LinkedHashMap<>(); row.put("id",record.getId()+":"+matcher.group(1));
            row.put("account",matcher.group(1)); row.put("type",amount.signum()>0?"ADMIN_IN":"ADMIN_OUT");
            row.put("typeText",amount.signum()>0?"后台入金":"后台出金"); row.put("amount",amount);
            row.put("balanceBefore",new BigDecimal(matcher.group(2))); row.put("balanceAfter",new BigDecimal(matcher.group(3)));
            row.put("createdAt",record.getCreatedAt()); result.add(row);
        }
        return result;
    }
}
