package com.gtcfesk.exchange.config;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import javax.servlet.http.*;
import java.lang.reflect.Type;
import java.util.*;

/** Enforce existing role menus and agent actions before controller side effects. */
@ControllerAdvice
@RequiredArgsConstructor
public class BackendAccess extends RequestBodyAdviceAdapter implements HandlerInterceptor {
    private final AdminUserRepository admins;
    private final AdminRoleRepository roles;
    private final AdminRoleMenuRepository roleMenus;
    private final AdminMenuRepository menus;
    private final UserMenuRepository userMenus;
    private final UserActionRepository actions;
    private final UserAccountRepository users;
    private final DepositRecordRepository deposits;
    private final WithdrawRecordRepository withdrawals;
    private final LoanRecordRepository loans;
    private final KycRecordRepository kycs;
    private final LoanPersonalInfoRepository personalInfos;
    private final ContractOrderRepository contracts;
    private final OptionOrderRepository options;
    private final FinancialOrderRepository financialOrders;
    private final ObjectMapper mapper;
    private final AdminPermissionService permissions;

    public static Long agentId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_AGENT"))
                ? Long.valueOf(auth.getName().substring(6)) : null;
    }
    private void deny() { throw new AccessDeniedException("无权执行此操作"); }
    public void checkUser(Long userId) {
        if (userId == null || !users.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId).isPresent()) deny();
        Long agent = agentId();
        if (agent != null && (userId == null || !users.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId).map(u -> agent.equals(u.getParentUserId())).orElse(false))) deny();
    }
    private boolean superAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
    private void checkMenu(String code, String action) { permissions.require(code, action); }
    public void checkDeposit(String action) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a ->
                Arrays.asList("ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_AGENT").contains(a.getAuthority()))) deny();
        checkMenu("deposit_orders", action);

    }
    public void checkDepositReview(String action) { permissions.requireAny("deposit_review:" + action, "deposit_orders:" + action); }
    public boolean canReadMenu(String code) {
        try { checkMenu(code, null); return true; }
        catch (AccessDeniedException e) { return false; }
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) return true;
        HandlerMethod h = (HandlerMethod) handler;
        String c = h.getBeanType().getSimpleName(), m = h.getMethod().getName();
        if (c.equals("ControlExchangeController")) return true;
        if (c.equals("AdminAuthController")) {
            if (com.gtcfesk.exchange.control.ControlIdentity.isAccess()) deny();
            if (!m.equals("login") && m.contains("Agent") != (agentId() != null)) deny();
            return true;
        }
        if (c.equals("AdminTablePreferenceController")) return true; // Controller restricts preferences to the authenticated backend account.
        if (c.equals("AdminAccountQueryController")) return true; // Controller checks each allowlisted read against live module permissions.
        if (c.equals("DepositOrderController")) return true; // Every method checks explicit module action and scope.
        if (c.equals("AdminUserController") && m.equals("updateBalance")) return true; // Body selects strict set vs deposit permission.
        if (superAdmin()) return true;
        // Deleting/restoring trade history is restricted to administrators, never agents.
        if (c.equals("AdminOrderController") && (m.startsWith("softDelete") || m.startsWith("restore")) && agentId() != null) deny();
        // Campaigns are global and lack agent-scoped delivery queries.
        Long agent = agentId();
        if (agent != null && c.equals("AdminActivityController")) deny();
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null) vars = Collections.emptyMap();
        if (c.equals("AdminMenuController")) return true; // Catalog and signed-in subject's own snapshot only.
        if (c.equals("AgentMenuController") || (c.equals("AdminUserController") && m.equals("getAgentMenus"))) {
            String id = vars.getOrDefault("agentId", vars.get("userId"));
            if (agent != null && (id == null || agent.toString().equals(id))) return true;
            permissions.require("agents", "assign_permission");
            return true;
        }
        if (c.equals("NotificationController") || (c.equals("AdminUserController") && m.equals("getOnlineUserCount"))) return true;
        if (c.equals("AdminUserController") && m.equals("getMenuActions")) return true;
        if (c.equals("AdminUserController") && m.equals("getAgentsSimple")) {
            permissions.requireAny("users", "orders", "statistics", "deposit_review", "withdraw_review", "loan_review", "kyc_review", "loan_personal_info_review", "deposit_orders");
            return true;
        }
        if (c.equals("SystemConfigController")) {
            if (m.equals("getConfig")) {
                String key = request.getParameter("key");
                if ("agent.default.permissions".equals(key)) permissions.require("agents", "defaults");
                else if (com.gtcfesk.exchange.market.MarketHoursConfig.KEY.equals(key)) permissions.require("market_hours", "");
                else if ("share.templates".equals(key)) permissions.requireAny("share_templates", "settings", "orders");
                else if ("share.materials".equals(key)) permissions.require("share_templates", "");
                else permissions.require("settings", "");
            } else if (m.equals("getAllConfigs")) permissions.require("settings", "");
            // Every write key is checked after body conversion, before the transactional controller runs.
            else if (!m.equals("saveConfig") && !m.equals("saveBatchConfig")) deny();
            return true;
        }
        AdminPermission permission = h.getMethodAnnotation(AdminPermission.class);
        if (permission == null) { deny(); return false; }
        boolean special = false;
        if (c.equals("AdminUserController")) {
            if (m.equals("getUsersWithParams") && "agent".equals(request.getParameter("userType"))) {
                permissions.require("agents", ""); special = true;
            }
            if (Arrays.asList("getSubordinates", "updateRemark", "updateUserStatus").contains(m) && vars.containsKey("userId") &&
                users.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), Long.valueOf(vars.get("userId"))).map(u -> "agent".equals(u.getUserType())).orElse(false)) {
                permissions.requireAny("users:" + permission.action(), "agents:" + (m.equals("getSubordinates") ? "view_subordinates" : m.equals("updateRemark") ? "modify_remark" : "status")); special = true;
            }
        }
        if (!special) permissions.require(permission.menu(), permission.action());
        if (agent != null && Arrays.asList("AdminManagementController", "AdminRoleController").contains(c)) deny();
        if (c.equals("WebsiteSecurityController") || m.equals("updateUserType") || m.equals("batchUpdateIpRegions")) deny();
        if (agent != null) {
            String filter = request.getParameter("filterAgentId");
            if (filter != null && !agent.toString().equals(filter)) deny();
            if (vars.containsKey("userId")) {
                if (m.equals("getSubordinates")) { if (!agent.toString().equals(vars.get("userId"))) deny(); }
                else checkUser(Long.valueOf(vars.get("userId")));
            }
            if (vars.containsKey("agentId") && !agent.toString().equals(vars.get("agentId"))) deny();
            String recordId = vars.getOrDefault("orderId", vars.get("id"));
            // Only these owner-record controllers use numeric ids; insight ids are UUIDs.
            if (recordId != null && Arrays.asList("DepositReviewController", "WithdrawReviewController", "LoanReviewController",
                    "KycReviewController", "LoanPersonalInfoReviewController", "AdminOrderController", "AdminFinancialYieldController").contains(c)) {
                Long id = Long.valueOf(recordId), owner = null;
                if (c.equals("DepositReviewController")) owner = deposits.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).map(DepositRecord::getUserId).orElse(null);
                else if (c.equals("WithdrawReviewController")) owner = withdrawals.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).map(WithdrawRecord::getUserId).orElse(null);
                else if (c.equals("LoanReviewController")) owner = loans.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).map(LoanRecord::getUserId).orElse(null);
                else if (c.equals("KycReviewController")) owner = kycs.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).map(KycRecord::getUserId).orElse(null);
                else if (c.equals("LoanPersonalInfoReviewController")) owner = personalInfos.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).map(LoanPersonalInfo::getUserId).orElse(null);
                else if (c.equals("AdminOrderController")) owner = request.getRequestURI().contains("/contract/")
                        ? contracts.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).map(ContractOrder::getUserId).orElse(null) : options.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).map(OptionOrder::getUserId).orElse(null);
                else if (c.equals("AdminFinancialYieldController")) owner = financialOrders.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).map(FinancialOrder::getUserId).orElse(null);
                if (owner != null) checkUser(owner);
            }
        }
        return true;
    }
    @Override
    public boolean supports(MethodParameter p, Type t, Class<? extends HttpMessageConverter<?>> converter) {
        return p.getContainingClass().getPackage().getName().equals("com.gtcfesk.exchange.admin");
    }
    @Override
    public Object afterBodyRead(Object body, HttpInputMessage message, MethodParameter p, Type t, Class<? extends HttpMessageConverter<?>> converter) {
        Long agent = agentId();
        if (p.getMethod().getName().equals("updateBalance")) {
            JsonNode input = mapper.valueToTree(body);
            if (input.hasNonNull("amount")) checkDeposit("manual_deposit");
            else if (!superAdmin()) {
                checkMenu("users", "modify_balance");
            }
            if (input.hasNonNull("userId")) checkUser(input.get("userId").asLong());
        }
        JsonNode node = mapper.valueToTree(body);
        if (agent != null && node.hasNonNull("filterAgentId") && node.get("filterAgentId").asLong() != agent) deny();
        if (agent != null && node.hasNonNull("agentId") && node.get("agentId").asLong() != agent) deny();
        if (node.hasNonNull("userId")) checkUser(node.get("userId").asLong());
        String method = p.getMethod().getName();
        if (p.getContainingClass().getSimpleName().equals("SystemConfigController") && (method.equals("saveConfig") || method.equals("saveBatchConfig"))) {
            Iterable<JsonNode> configs = node.isArray() ? node : Collections.singletonList(node);
            for (JsonNode cfg : configs) {
                String key = cfg.path("key").asText();
                if (com.gtcfesk.exchange.market.MarketHoursConfig.KEY.equals(key)) throw new AccessDeniedException("请通过休市设置接口修改，不能使用通用配置接口");
                if ("agent.default.permissions".equals(key)) { if (!superAdmin()) deny(); }
                else if ("support.settings".equals(key)) { if (agent != null) deny(); permissions.require("support_settings", "save"); }
                else if ("share.templates".equals(key) || "share.materials".equals(key)) { if (agent != null) deny(); permissions.require("share_templates", "save"); }
                else permissions.require("settings", "save");
            }
        }
        if (method.equals("setPresetProfitType")) {
            String preset = node.path("presetType").asText();
            if (!"PROFIT".equals(preset) && !"LOSS".equals(preset)) deny();
            checkMenu("orders", "PROFIT".equals(preset) ? "set_profit" : "set_loss");
        }
        if (method.equals("updateStatus") || method.equals("updateUserStatus")) {
            String status = node.path("status").asText();
            String action;
            if (status.equals("frozen")) action = "freeze_user";
            else if (status.equals("normal") || status.equals("active")) {
                Long target = node.hasNonNull("userId") ? node.get("userId").asLong() : null;
                org.springframework.web.context.request.ServletRequestAttributes attributes = (org.springframework.web.context.request.ServletRequestAttributes)org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
                if (target == null && attributes != null) {
                    Map<?,?> vars = (Map<?,?>)attributes.getRequest().getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                    if (vars != null && vars.get("userId") != null) target = Long.valueOf(vars.get("userId").toString());
                }
                action = target != null && users.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), target).map(u -> Arrays.asList("banned", "disabled").contains(u.getStatus())).orElse(false) ? "unban_user" : "unfreeze_user";
            }
            else if (status.equals("banned") || status.equals("disabled")) action = "ban_user";
            else { deny(); return body; }
            org.springframework.web.context.request.ServletRequestAttributes attrs = (org.springframework.web.context.request.ServletRequestAttributes)org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            Map<?,?> path = attrs == null ? null : (Map<?,?>)attrs.getRequest().getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            Long target = node.hasNonNull("userId") ? node.get("userId").asLong() : path != null && path.get("userId") != null ? Long.valueOf(path.get("userId").toString()) : null;
            if (target != null && users.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), target).map(u -> "agent".equals(u.getUserType())).orElse(false)) permissions.requireAny("users:" + action, "agents:status");
            else checkMenu("users", action);
        }
        return body;
    }
}
