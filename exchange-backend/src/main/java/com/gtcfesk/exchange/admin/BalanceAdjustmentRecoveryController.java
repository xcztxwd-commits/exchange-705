package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.admin.dto.UpdateUserBalanceRequest;
import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.user.BalanceAdjustmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class BalanceAdjustmentRecoveryController {
    private final BalanceAdjustmentService adjustments;

    @PostMapping("/api/admin/users/balance-adjustments/reconcile")
    @AdminPermission(menu = "users", action = "modify_balance")
    public Map<String, Object> reconcile(@RequestBody UpdateUserBalanceRequest input) {
        return adjustments.reconcile(input);
    }
}
