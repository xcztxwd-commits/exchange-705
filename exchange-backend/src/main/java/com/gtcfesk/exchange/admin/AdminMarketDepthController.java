package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.market.MarketDepthService;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin/market/depth")
public class AdminMarketDepthController {
    @Autowired MarketDepthService depth;
    @Autowired SystemConfigService configs;
    @GetMapping("/status") @AdminPermission(menu="settings") public Object status(){return depth.status();}
    @PutMapping("/settings") @AdminPermission(menu="settings",action="save") @Transactional
    public Object settings(@RequestBody Map<String,Object> request){
        if(request.size()!=1||!(request.get("enabled") instanceof Boolean))throw new com.gtcfesk.exchange.common.BusinessException("enabled 必须为布尔值");
        boolean enabled=(Boolean)request.get("enabled");configs.saveConfig(MarketDepthService.ENABLED_KEY,Boolean.toString(enabled),"本租户外部参考深度开关");
        if(!enabled){Long tenant=TenantContext.requireTenantId();org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){depth.disableTenant(tenant);}});}
        return Collections.singletonMap("enabled",enabled);
    }
}
