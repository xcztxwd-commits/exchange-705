package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.market.MarketHoursConfig;
import com.gtcfesk.exchange.market.MarketHoursService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/market-hours") @RequiredArgsConstructor
public class AdminMarketHoursController {
    private final MarketHoursService hours;
    private final com.gtcfesk.exchange.control.ControlAuditService audit;
    @GetMapping @AdminPermission(menu="market_hours") public Object list(){return hours.view();}
    @PutMapping @AdminPermission(menu="market_hours",action="save")
    @org.springframework.transaction.annotation.Transactional
    public Object save(@RequestBody MarketHoursConfig.Settings input){Object result=hours.save(input);
        if(com.gtcfesk.exchange.control.ControlIdentity.isAccess())audit.recordCurrent("MARKET_HOURS_SAVE",MarketHoursConfig.KEY,"strategies and bindings changed; revision="+input.revision,null);
        return result;}
    @PostMapping("/override") @AdminPermission(menu="market_hours",action="manual")
    @org.springframework.transaction.annotation.Transactional
    public Object override(@RequestBody MarketHoursService.OverrideInput input){Object result=hours.override(input);
        if(com.gtcfesk.exchange.control.ControlIdentity.isAccess())audit.recordCurrent("MARKET_HOURS_OVERRIDE",input.scope+":"+input.target,"mode="+input.mode,input.reason);
        return result;}
}
