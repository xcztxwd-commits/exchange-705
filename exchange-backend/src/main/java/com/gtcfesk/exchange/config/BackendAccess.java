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

    public static Long agentId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_AGENT"))
                ? Long.valueOf(auth.getName().substring(6)) : null;
    }
    private void deny() { throw new AccessDeniedException("无权执行此操作"); }
    public void checkUser(Long userId) {
        Long agent = agentId();
        if (agent != null && (userId == null || !users.findById(userId).map(u -> agent.equals(u.getParentUserId())).orElse(false))) deny();
    }
    private boolean superAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
    private String menu(String controller, String method) {
        switch (controller) {
            case "AdminUserController": case "AdminWalletController": return "users";
            case "AdminOrderController": return "orders";
            case "AdminSymbolController": return "symbols";
            case "AdminAiControlController": return "ai_control";
            case "AdminDurationController": return "durations";
            case "DepositReviewController": return "deposit_review";
            case "WithdrawReviewController": return "withdraw_review";
            case "LoanReviewController": return "loan_review";
            case "LoanPersonalInfoReviewController": return "loan_personal_info_review";
            case "KycReviewController": return "kyc_review";
            case "DepositSettingController": return "deposit_settings";
            case "LoanSettingController": return "loan_settings";
            case "AdminFinancialController": return method.equals("getOrders") ? "financial_orders" : "financial_products";
            case "AdminFinancialYieldController": return "financial_orders";
            case "AdminAnnouncementController": return "announcement";
            case "DashboardController": return "dashboard";
            case "AgentPerformanceController": return "agents";
            case "StatisticsController": return "statistics";
            case "OperationLogController": return "operation_log";
            case "AdminRoleController": return "roles";
            case "AdminManagementController": return "admin_list";
            case "SystemConfigController": return "settings";
            default: return null;
        }
    }
    private void checkMenu(String code, String action) {
        if (superAdmin()) return;
        AdminMenu menu = menus.findByMenuCode(code).orElseGet(() -> menus.findByMenuCode(code.replace('_', '-')).orElse(null));
        if (menu == null || !"active".equals(menu.getStatus())) { deny(); return; }
        Long agent = agentId();
        if (agent != null) {
            if (!userMenus.existsByUserIdAndMenuId(agent, menu.getId()) ||
                (action != null && !actions.existsByUserIdAndMenuIdAndActionCode(agent, menu.getId(), action))) deny();
        } else {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            AdminUser admin = admins.findById(Long.valueOf(auth.getName())).orElseThrow(() -> new AccessDeniedException("无权访问"));
            AdminRole role = roles.findByRoleCode(admin.getRole()).orElseThrow(() -> new AccessDeniedException("角色未授权"));
            if (!"active".equals(role.getStatus()) || roleMenus.findByRoleId(role.getId()).stream().noneMatch(m -> menu.getId().equals(m.getMenuId()))) deny();
        }
    }
    public boolean canReadMenu(String code) {
        try { checkMenu(code, null); return true; }
        catch (AccessDeniedException e) { return false; }
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) return true;
        HandlerMethod h = (HandlerMethod) handler;
        String c = h.getBeanType().getSimpleName(), m = h.getMethod().getName();
        if (c.equals("AdminAuthController")) {
            if (!m.equals("login") && m.contains("Agent") != (agentId() != null)) deny();
            return true;
        }
        if (superAdmin()) return true;
        Long agent = agentId();
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null) vars = Collections.emptyMap();
        if (c.equals("AdminMenuController")) return true;
        if (c.equals("AgentMenuController") || (c.equals("AdminUserController") && m.equals("getAgentMenus"))) {
            String id = vars.getOrDefault("agentId", vars.get("userId"));
            if (agent == null || (id != null && !agent.toString().equals(id))) deny();
            return true;
        }
        if (c.equals("NotificationController")) return true; // Controller scopes counts to the signed agent.
        if (c.equals("AdminUserController") && m.equals("getOnlineUserCount")) return true; // Controller filters by menu and agent scope.
        if (c.equals("AdminManagementController") || c.equals("AdminRoleController") || c.equals("SystemConfigController") ||
            m.equals("assignMenus") || m.equals("updateUserType") || m.equals("batchUpdateIpRegions")) { deny(); }
        if (agent == null && (m.equals("updateBalance") || m.startsWith("abnormalDelete"))) deny();
        String code = menu(c, m);
        if (code == null) { deny(); return false; }
        String action = null;
        boolean read = request.getMethod().equals("GET") || m.startsWith("query");
        if (!read && agent != null) {
            switch (m) {
                case "updateSymbol": case "toggleHot": case "batchSetLeverage": action = "edit_symbol"; break;
                case "deleteSymbol": action = "delete_symbol"; break;
                case "resetPassword": action = "reset_password"; break;
                case "updateBalance": action = "modify_balance"; break;
                case "updateStatus": case "updateUserStatus": break; // Check destination status after body conversion.
                case "updateInviteCode": action = "modify_invite_code"; break;
                case "updateRemark": action = "modify_remark"; break;
                case "deleteUser": action = "delete_user"; break;
                case "approveDeposit": action = "approve_deposit"; break;
                case "rejectDeposit": action = "reject_deposit"; break;
                case "approveWithdraw": action = "approve_withdraw"; break;
                case "rejectWithdraw": action = "reject_withdraw"; break;
                case "completeWithdraw": action = "complete_withdraw"; break;
                case "approveLoan": action = "approve_loan"; break;
                case "rejectLoan": action = "reject_loan"; break;
                case "setPresetProfitType": break;
                case "startControl": case "manualControl": case "stopControl": case "restoreControl": break;
                case "approvePersonalInfo": action = "approve_loan_personal_info"; break;
                case "rejectPersonalInfo": action = "reject_loan_personal_info"; break;
                case "approveKyc": action = "approve_kyc"; break;
                case "rejectKyc": action = "reject_kyc"; break;
                case "adminCloseOrder": action = "close_order"; break;
                case "adminCancelOrder": action = "cancel_order"; break;
                case "clearPresetProfitType": action = "clear_preset"; break;
                default:
                    if (c.equals("AdminWalletController")) action = "wallet_management";
                    else { deny(); }
            }
        }
        checkMenu(code, action);
        if (agent != null) {
            String filter = request.getParameter("filterAgentId");
            if (filter != null && !agent.toString().equals(filter)) deny();
            if (vars.containsKey("userId")) {
                if (m.equals("getSubordinates")) { if (!agent.toString().equals(vars.get("userId"))) deny(); }
                else checkUser(Long.valueOf(vars.get("userId")));
            }
            if (vars.containsKey("agentId") && !agent.toString().equals(vars.get("agentId"))) deny();
            String recordId = vars.getOrDefault("orderId", vars.get("id"));
            if (recordId != null) {
                Long id = Long.valueOf(recordId), owner = null;
                if (c.equals("DepositReviewController")) owner = deposits.findById(id).map(DepositRecord::getUserId).orElse(null);
                else if (c.equals("WithdrawReviewController")) owner = withdrawals.findById(id).map(WithdrawRecord::getUserId).orElse(null);
                else if (c.equals("LoanReviewController")) owner = loans.findById(id).map(LoanRecord::getUserId).orElse(null);
                else if (c.equals("KycReviewController")) owner = kycs.findById(id).map(KycRecord::getUserId).orElse(null);
                else if (c.equals("LoanPersonalInfoReviewController")) owner = personalInfos.findById(id).map(LoanPersonalInfo::getUserId).orElse(null);
                else if (c.equals("AdminOrderController")) owner = request.getRequestURI().contains("/contract/")
                        ? contracts.findById(id).map(ContractOrder::getUserId).orElse(null) : options.findById(id).map(OptionOrder::getUserId).orElse(null);
                else if (c.equals("AdminFinancialYieldController")) owner = financialOrders.findById(id).map(FinancialOrder::getUserId).orElse(null);
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
        if (agent == null) return body;
        JsonNode node = mapper.valueToTree(body);
        if (node.hasNonNull("filterAgentId") && node.get("filterAgentId").asLong() != agent) deny();
        if (node.hasNonNull("agentId") && node.get("agentId").asLong() != agent) deny();
        if (node.hasNonNull("userId")) checkUser(node.get("userId").asLong());
        String method = p.getMethod().getName();
        if (method.equals("setPresetProfitType")) {
            String preset = node.path("presetType").asText();
            if (!"PROFIT".equals(preset) && !"LOSS".equals(preset)) deny();
            checkMenu("orders", "PROFIT".equals(preset) ? "set_profit" : "set_loss");
        }
        if (method.equals("updateStatus") || method.equals("updateUserStatus")) {
            String status = node.path("status").asText();
            String action;
            if (status.equals("frozen")) action = "freeze_user";
            else if (status.equals("normal") || status.equals("active")) action = "unfreeze_user";
            else if (status.equals("banned") || status.equals("disabled")) action = "ban_user";
            else { deny(); return body; }
            checkMenu("users", action);
        }
        return body;
    }
}
