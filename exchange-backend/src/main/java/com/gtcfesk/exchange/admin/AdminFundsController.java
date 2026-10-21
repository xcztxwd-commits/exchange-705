package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.admin.dto.FundsLedgerQuery;
import com.gtcfesk.exchange.config.AdminPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/funds")
public class AdminFundsController {
    private final AdminFundsService service;
    private final AdminPermissionService permissions;

    @GetMapping("/ledger")
    @AdminPermission(menu = "funds_ledger")
    public Map<String, Object> ledger(@Valid FundsLedgerQuery query) {
        permissions.require("funds_ledger", "view");
        return service.query(query);
    }
}
