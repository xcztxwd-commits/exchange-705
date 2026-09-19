package com.gtcfesk.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.AdminUser;
import com.gtcfesk.exchange.admin.AdminUserRepository;
import com.gtcfesk.exchange.admin.OperationLogService;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.OperationLog;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OperationLogInterceptor implements HandlerInterceptor {

    private final OperationLogService operationLogService;
    private final JwtUtil jwtUtil;
    private final AdminUserRepository adminUserRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        try {
            // 只记录管理员操作（/api/admin/** 路径，排除操作日志查询本身）
            String requestURI = request.getRequestURI();
            if (!requestURI.startsWith("/api/admin/") || requestURI.startsWith("/api/admin/operation-logs")) {
                return;
            }

            // 排除GET请求（查询操作，减少日志量）
            String method = request.getMethod();
            if ("GET".equals(method)) {
                return;
            }

            // 获取管理员信息
            Long adminId = getAdminIdFromRequest(request);
            if (adminId == null) {
                return; // 不是管理员操作，不记录
            }

            AdminUser admin = adminUserRepository.findById(adminId).orElse(null);
            if (admin == null) {
                return;
            }

            // 创建操作日志
            OperationLog log = new OperationLog();
            log.setAdminId(adminId);
            log.setAdminEmail(admin.getEmail());
            log.setRequestMethod(method);
            log.setRequestUrl(requestURI);
            log.setIpAddress(getClientIpAddress(request));
            log.setUserAgent(request.getHeader("User-Agent"));

            // 解析操作类型和操作动作
            parseOperationInfo(requestURI, method, log);

            // 获取请求参数
            try {
                if (request instanceof ContentCachingRequestWrapper) {
                    ContentCachingRequestWrapper wrappedRequest = (ContentCachingRequestWrapper) request;
                    byte[] content = wrappedRequest.getContentAsByteArray();
                    if (content.length > 0) {
                        String body = new String(content, wrappedRequest.getCharacterEncoding());
                        body = com.gtcfesk.exchange.common.LogRedaction.sanitize(body);
                        log.setRequestParams(body);
                    }
                } else {
                    // 如果不是包装的请求，尝试从参数中获取
                    Map<String, String[]> parameterMap = request.getParameterMap();
                    if (!parameterMap.isEmpty()) {
                        Map<String, Object> params = new HashMap<>();
                        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
                            String[] values = entry.getValue();
                            if (values != null && values.length > 0) {
                                params.put(entry.getKey(), values.length == 1 ? values[0] : values);
                            }
                        }
                        String paramsJson = objectMapper.writeValueAsString(params);
                        paramsJson = com.gtcfesk.exchange.common.LogRedaction.sanitize(paramsJson);
                        log.setRequestParams(paramsJson);
                    }
                }
            } catch (Exception e) {
                // 忽略参数解析错误
                log.setRequestParams("参数解析失败");
            }

            // 设置状态
            if (ex != null || response.getStatus() >= 400) {
                log.setStatus("FAILED");
                if (ex != null) {
                    String errorMsg = com.gtcfesk.exchange.common.SafeErrors.message(ex);
                    if (errorMsg != null && errorMsg.length() > 1000) {
                        errorMsg = errorMsg.substring(0, 1000) + "...(truncated)";
                    }
                    log.setErrorMessage(errorMsg);
                }
            } else {
                log.setStatus("SUCCESS");
            }

            // 异步保存日志（避免影响主流程）
            try {
                operationLogService.logOperation(log);
            } catch (Exception e) {
                // 记录日志失败不影响主流程，只打印错误
                System.err.println("记录操作日志失败: " + e.getMessage());
            }
        } catch (Exception e) {
            // 拦截器异常不影响主流程
            System.err.println("操作日志拦截器异常: " + e.getMessage());
        }
    }

    /**
     * 从请求中获取管理员ID
     */
    private Long getAdminIdFromRequest(HttpServletRequest request) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_SUPER_ADMIN"))) {
            return Long.valueOf(auth.getName());
        }
        return null;
    }

    /**
     * 解析操作类型和操作动作
     */
    private void parseOperationInfo(String requestURI, String method, OperationLog log) {
        // 根据URL路径推断操作类型
        String operationType = "其他";
        String operationAction = method;
        String targetType = null;
        Long targetId = null;

        // 解析URL路径
        String[] parts = requestURI.split("/");
        
        // 查找路径中的ID（通常是最后一个数字）
        for (int i = parts.length - 1; i >= 0; i--) {
            try {
                if (parts[i].matches("\\d+")) {
                    targetId = Long.parseLong(parts[i]);
                    break;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // 根据路径判断操作类型
        if (requestURI.contains("/users")) {
            operationType = "用户管理";
            targetType = "用户";
            if (requestURI.contains("/resetPassword")) {
                operationAction = "重置密码";
            } else if (requestURI.contains("/updateStatus")) {
                operationAction = "更新状态";
            } else if (requestURI.contains("/updateUserType")) {
                operationAction = "更新用户类型";
            } else if (requestURI.contains("/updateBalance")) {
                operationAction = "更新余额";
            } else if (requestURI.contains("/update-remark")) {
                operationAction = "更新备注";
            } else if (requestURI.contains("/update-invite-code")) {
                operationAction = "更新邀请码";
            } else if (method.equals("POST")) {
                operationAction = "创建用户";
            } else if (method.equals("PUT")) {
                operationAction = "更新用户";
            } else if (method.equals("DELETE")) {
                operationAction = "删除用户";
            }
        } else if (requestURI.contains("/orders")) {
            operationType = "订单管理";
            targetType = "订单";
            if (method.equals("POST")) {
                operationAction = "创建订单";
            } else if (method.equals("PUT")) {
                operationAction = "更新订单";
            } else if (method.equals("DELETE")) {
                operationAction = "删除订单";
            }
        } else if (requestURI.contains("/deposit-review") || requestURI.contains("/depositReview")) {
            operationType = "充值审核";
            targetType = "充值记录";
            if (requestURI.contains("/approve")) {
                operationAction = "审核通过";
            } else if (requestURI.contains("/reject")) {
                operationAction = "审核拒绝";
            }
        } else if (requestURI.contains("/withdraw-review") || requestURI.contains("/withdrawReview")) {
            operationType = "提现审核";
            targetType = "提现记录";
            if (requestURI.contains("/approve")) {
                operationAction = "审核通过";
            } else if (requestURI.contains("/reject")) {
                operationAction = "审核拒绝";
            }
        } else if (requestURI.contains("/kyc-review") || requestURI.contains("/kycReview")) {
            operationType = "实名审核";
            targetType = "实名记录";
            if (requestURI.contains("/approve")) {
                operationAction = "审核通过";
            } else if (requestURI.contains("/reject")) {
                operationAction = "审核拒绝";
            }
        } else if (requestURI.contains("/loan-review") || requestURI.contains("/loanReview")) {
            operationType = "贷款审核";
            targetType = "贷款记录";
            if (requestURI.contains("/approve")) {
                operationAction = "审核通过";
            } else if (requestURI.contains("/reject")) {
                operationAction = "审核拒绝";
            }
        } else if (requestURI.contains("/loan-personal-info-review")) {
            operationType = "个人信息审核";
            targetType = "个人信息";
            if (requestURI.contains("/approve")) {
                operationAction = "审核通过";
            } else if (requestURI.contains("/reject")) {
                operationAction = "审核拒绝";
            }
        } else if (requestURI.contains("/symbols")) {
            operationType = "币种管理";
            targetType = "币种";
            if (method.equals("POST")) {
                operationAction = "创建币种";
            } else if (method.equals("PUT")) {
                operationAction = "更新币种";
            } else if (method.equals("DELETE")) {
                operationAction = "删除币种";
            }
        } else if (requestURI.contains("/roles")) {
            operationType = "角色管理";
            targetType = "角色";
            if (method.equals("POST")) {
                operationAction = "创建角色";
            } else if (method.equals("PUT")) {
                operationAction = "更新角色";
            } else if (method.equals("DELETE")) {
                operationAction = "删除角色";
            }
        } else if (requestURI.contains("/agents")) {
            operationType = "代理管理";
            targetType = "代理";
            if (method.equals("POST")) {
                operationAction = "创建代理";
            } else if (method.equals("PUT")) {
                operationAction = "更新代理";
            } else if (method.equals("DELETE")) {
                operationAction = "删除代理";
            }
        } else if (requestURI.contains("/announcement")) {
            operationType = "公告管理";
            targetType = "公告";
            if (method.equals("POST")) {
                operationAction = "创建公告";
            } else if (method.equals("PUT")) {
                operationAction = "更新公告";
            } else if (method.equals("DELETE")) {
                operationAction = "删除公告";
            }
        } else if (requestURI.contains("/settings") || requestURI.contains("/config")) {
            operationType = "系统配置";
            operationAction = "更新配置";
        } else if (requestURI.contains("/auth/profile")) {
            operationType = "管理员设置";
            if (requestURI.contains("/account")) {
                operationAction = "更新登录名称";
            } else if (requestURI.contains("/password")) {
                operationAction = "修改密码";
            }
        }

        log.setOperationType(operationType);
        log.setOperationAction(operationAction);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        if (targetId != null) {
            log.setTargetInfo(targetType + " ID: " + targetId);
        }
    }

    /**
     * 获取客户端IP地址
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 如果是多个IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}

