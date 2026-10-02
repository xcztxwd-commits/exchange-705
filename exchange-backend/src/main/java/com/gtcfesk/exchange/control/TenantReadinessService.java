package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.repository.SystemConfigRepository;
import com.gtcfesk.exchange.security.OutboundEndpointPolicy;
import com.gtcfesk.exchange.service.EmailService;
import com.gtcfesk.exchange.support.SupportSettings;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantSecrets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.persistence.EntityManager;
import java.time.ZoneId;
import java.util.*;

/** Configuration evidence, not a client-supplied readiness checkbox. Never returns secret values. */
@Service @RequiredArgsConstructor
public class TenantReadinessService {
    private final TenantRepository tenants;
    private final TenantPolicyRepository policies;
    private final SystemConfigRepository configs;
    private final TenantSecrets secrets;
    private final OutboundEndpointPolicy outbound;
    private final EntityManager em;
    public static class Missing {
        public final String key,message,owner,location;
        Missing(String key,String message){this.key=key;this.message=message;String[] target=location(key);this.owner=target[0];this.location=target[1];}
    }
    public static class Report {
        public final Long tenantId;public final boolean ready;public final List<Missing> missing;
        Report(Long tenantId,List<Missing> missing){this.tenantId=tenantId;this.missing=Collections.unmodifiableList(missing);this.ready=missing.isEmpty();}
    }
    @Transactional(readOnly=true) public Report report(Long tenant){return inspect(tenant,null);}
    public void requireReady(Long tenant){require(inspect(tenant,null));}
    public void requireFeatureReady(Long tenant,String feature){if(!TenantPolicyService.FEATURES.contains(feature))throw new IllegalArgumentException("未知功能");require(inspect(tenant,feature));}
    private static void require(Report report){if(!report.ready)throw new BusinessException("租户配置未就绪："+report.missing.stream().map(m->m.key+"（"+m.message+"）").collect(java.util.stream.Collectors.joining("；")));}
    private Report inspect(Long tenant,String onlyFeature){
        Tenant registration=tenants.findById(tenant).orElseThrow(ControlService::invalid);
        try(TenantContext.Scope scope=TenantContext.open(tenant)){
            List<Missing> missing=new ArrayList<>();
            if(registration.getFrontendHost()==null||!registration.isDomainVerified())add(missing,"frontendHost","域名与 HTTPS 路由尚未验证");
            String site=value("site.name");if(blank(site)||"未配置平台".equals(site))add(missing,"site.name","请填写实际平台名称");
            check(missing,"system.timezone",()->ZoneId.of(value("system.timezone")));
            if(!exists("AdminUser","e.enabled=true AND e.role='super_admin' AND EXISTS (SELECT b.id FROM BackendLogin b WHERE b.tenantId=e.tenantId AND b.adminUserId=e.id AND b.enabled=true AND b.subjectType='ADMIN')"))add(missing,"backend.admin","至少一个有效租户负责人及其全局登录项");
            Set<String> features=new HashSet<>();
            if(onlyFeature!=null)features.add(onlyFeature);else for(TenantPolicy p:policies.findByTenantId(tenant))if(p.getKey().startsWith("feature.")&&"true".equals(p.getValue()))features.add(p.getKey().substring(8));
            if(features.contains("registration")){
                check(missing,"mail.endpoint",()->outbound.smtp(value("mail.host"),value("mail.port")));
                for(String key:Arrays.asList("mail.username","mail.password"))check(missing,key,()->{if(blank(value(key)))throw new BusinessException("必需配置缺失");});
                check(missing,"mail.from",()->EmailService.address(value("mail.from")));
            }
            if(features.contains("contract")||features.contains("option")){
                if(!exists("TradingSymbol","e.isEnabled=true AND e.marketSource IS NOT NULL AND e.marketSource<>'' AND e.sourceCategory IS NOT NULL AND e.sourceCategory<>'' AND e.baseCurrency<>'' AND e.quoteCurrency<>''"))add(missing,"market.symbol","至少一个启用且来源、币种完整的交易品种");
                if(exists("TradingSymbol","e.isEnabled=true AND UPPER(e.marketSource)='ALLTICK'"))check(missing,"market.quote.token",()->{if(blank(value("market.quote.token")))throw new BusinessException("行情源密钥缺失");});
            }
            if(features.contains("option")&&!exists("OptionDuration","e.enabled=true AND e.duration>0 AND e.profitRate>=0 AND e.lossRate>=0 AND e.lossRate<=1 AND e.minAmount>0 AND e.maxAmount>=e.minAmount"))add(missing,"option.duration","至少一个有效期限、收益率和金额范围");
            if(features.contains("financial")&&!exists("FinancialProduct","e.enabled=true AND e.termDays>0 AND e.dailyYieldRate>=0 AND e.rentalFee>=0 AND e.minPurchase>0 AND e.maxPurchase>=e.minPurchase AND e.penaltyRate>=0 AND e.penaltyRate<=100 AND e.currency<>''"))add(missing,"financial.product","至少一个完整启用产品及合法期限、收益率、金额、赎回费率");
            if(features.contains("loan")&&!exists("LoanSetting","e.enabled=true AND e.days>0 AND e.freeDays>=0 AND e.freeDays<=e.days AND e.dailyRate>=0 AND e.overdueRate>=0 AND e.minAmount>0 AND e.maxAmount>=e.minAmount"))add(missing,"loan.setting","至少一个启用贷款期限及合法费率、金额范围");
            if(features.contains("deposit")&&!exists("DepositSetting","e.enabled=true AND ((e.type='digital' AND e.network<>'' AND e.address<>'') OR (e.type='bank' AND e.bankName<>'' AND e.bankAccount<>'' AND e.accountName<>''))"))add(missing,"deposit.setting","至少一个完整启用的收款地址或银行账户");
            if(features.contains("external_support"))check(missing,"customer.service.link",()->outbound.https(value("customer.service.link"),"support"));
            if(features.contains("support")||features.contains("inbox"))check(missing,"support.settings",()->SupportSettings.parse(value("support.settings")));
            // Withdraw/agent/inbox/activity/simulation have no additional mandatory tenant provider credentials.
            // Runtime KYC/address/campaign-budget and isolated simulation gateway checks remain authoritative.
            return new Report(tenant,missing);
        }
    }
    private boolean exists(String entity,String predicate){return !em.createQuery("SELECT e.id FROM "+entity+" e WHERE e.tenantId=:tenant AND ("+predicate+")",Long.class).setParameter("tenant",TenantContext.requireTenantId()).setMaxResults(1).getResultList().isEmpty();}
    private String value(String key){String stored=configs.findByTenantIdAndConfigKey(TenantContext.requireTenantId(),key).map(c->c.getConfigValue()).orElse(null);String effective=policies.findByTenantIdAndKey(TenantContext.requireTenantId(),"config."+key).filter(TenantPolicy::isLocked).map(TenantPolicy::getValue).orElse(stored);return TenantSecrets.secret(key)?secrets.decrypt(key,effective):effective;}
    // Fixed locators are instructions, never caller-controlled redirects or shared authentication.
    static String[] location(String key){
        if("frontendHost".equals(key))return new String[]{"CONTROL","租户管理 / 域名：准备、核验、激活；运维先完成候选 HTTPS 与代理路由"};
        if("backend.admin".equals(key))return new String[]{"CONTROL","租户管理 / 后台账号：创建真实负责人或管理员；不得用隐藏员工代替总控访问"};
        if(key.startsWith("mail."))return new String[]{"TENANT_ADMIN","目标租户后台 /settings / 邮件配置；SMTP 目标须先获运维出站授权"};
        if("market.symbol".equals(key))return new String[]{"TENANT_ADMIN","目标租户后台 /symbols：审核来源、币种、单位、费用、精度后启用品种"};
        if("market.quote.token".equals(key))return new String[]{"TENANT_ADMIN","目标租户后台 /settings / 行情配置：经加密入口设置 Token，禁止写入明文策略"};
        if("option.duration".equals(key))return new String[]{"TENANT_ADMIN","目标租户后台 /durations：期限、收益率、亏损率、金额范围"};
        if("financial.product".equals(key))return new String[]{"TENANT_ADMIN","目标租户后台 /financial-products：产品期限、费率、币种和金额范围"};
        if("loan.setting".equals(key))return new String[]{"TENANT_ADMIN","目标租户后台 /loan-settings：期限、免息期、费率和金额范围"};
        if("deposit.setting".equals(key))return new String[]{"TENANT_ADMIN","目标租户后台 /deposit-settings：只录入批准的测试收款渠道；不得真实转账"};
        if("support.settings".equals(key))return new String[]{"TENANT_ADMIN","目标租户后台 /support-settings：站内客服 / 站内信；清理默认保持关闭"};
        if("customer.service.link".equals(key))return new String[]{"TENANT_ADMIN","目标租户后台 /settings / 客服配置：先核总控渠道锁定及 HTTPS 出站白名单"};
        if("site.name".equals(key)||"system.timezone".equals(key))return new String[]{"TENANT_ADMIN","目标租户后台 /settings / 系统设置：平台名称及 IANA 时区"};
        return new String[]{"OPERATIONS","未登记的配置项必须人工定位，不猜测设置页或默认置为就绪"};
    }
    private static boolean blank(String value){return value==null||value.trim().isEmpty()||TenantSecrets.MASK.equals(value);}
    private static void add(List<Missing> missing,String key,String message){missing.add(new Missing(key,message));}
    private static void check(List<Missing> missing,String key,Runnable action){try{action.run();}catch(Exception e){add(missing,key,e instanceof BusinessException?e.getMessage():"必需配置缺失或无效");}}
}
