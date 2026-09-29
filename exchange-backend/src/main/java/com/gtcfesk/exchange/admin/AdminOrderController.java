package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.entity.OptionOrder;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.ContractOrderRepository;
import com.gtcfesk.exchange.repository.OptionOrderRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.trade.ContractOrderService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final ContractOrderRepository contractOrderRepository;
    private final OptionOrderRepository optionOrderRepository;
    private final ContractOrderService contractOrderService;
    private final UserAccountRepository userAccountRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final JwtUtil jwtUtil;
    
    /**
     * 从JWT中提取代理ID
     */
    private Long extractAgentId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = authHeader.substring(7);
            Claims claims = jwtUtil.parse(token);
            String subject = claims.getSubject();
            if (subject != null && subject.startsWith("agent-")) {
                return Long.parseLong(subject.substring(6));
            }
        } catch (Exception ignored) {
            // 解析失败，返回null（管理员）
        }
        return null;
    }

    // Filter in SQL before pagination; deletion state is independent of trade status.
    private <T> org.springframework.data.jpa.domain.Specification<T> orderFilter(Map<String, Object> params, String authHeader) {
        Long agent = extractAgentId(authHeader);
        if (agent == null && params.get("filterAgentId") != null && !params.get("filterAgentId").toString().isEmpty())
            agent = Long.valueOf(params.get("filterAgentId").toString());
        final Set<Long> owners = agent == null ? null : userAccountRepository.findByParentUserId(agent).stream()
                .map(UserAccount::getId).collect(Collectors.toSet());
        String deletion = String.valueOf(params.getOrDefault("deletion", ""));
        if (!java.util.Arrays.asList("", "active", "deleted").contains(deletion)) throw new BusinessException("删除状态无效");
        return (root, query, cb) -> {
            java.util.List<javax.persistence.criteria.Predicate> filters = new java.util.ArrayList<>();
            if (owners != null) filters.add(owners.isEmpty() ? cb.disjunction() : root.get("userId").in(owners));
            for (String key : java.util.Arrays.asList("userId", "status")) {
                Object value = params.get(key);
                if (value != null && !value.toString().isEmpty()) filters.add(cb.equal(root.get(key), key.equals("userId") ? Long.valueOf(value.toString()) : value.toString()));
            }
            Object email = params.get("userEmail");
            if (email != null && !email.toString().trim().isEmpty()) {
                javax.persistence.criteria.Subquery<Long> users = query.subquery(Long.class);
                javax.persistence.criteria.Root<UserAccount> user = users.from(UserAccount.class);
                users.select(user.get("id")).where(cb.equal(user.get("email"), email.toString().trim()));
                filters.add(root.get("userId").in(users));
            }
            if (deletion.equals("active")) filters.add(cb.isNull(root.get("deletedAt")));
            if (deletion.equals("deleted")) filters.add(cb.isNotNull(root.get("deletedAt")));
            return cb.and(filters.toArray(new javax.persistence.criteria.Predicate[0]));
        };
    }

    @PostMapping("/contract/query")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "")
    public ResponseEntity<?> queryContractOrders(@RequestBody Map<String, Object> params,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        int page = Math.max(0, Integer.parseInt(params.getOrDefault("page", 0).toString()));
        int size = Math.max(1, Math.min(100, Integer.parseInt(params.getOrDefault("size", 20).toString())));
        Page<ContractOrder> orders = contractOrderRepository.findAll(orderFilter(params, authHeader),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        List<Map<String, Object>> list = orders.getContent().stream().map(order -> {
            fillAgentInfo(order);
            Map<String, Object> item = convertContractOrderToMap(order);
            userAccountRepository.findById(order.getUserId()).ifPresent(user -> item.put("userRemark", user.getRemark()));
            return item;
        }).collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("list", list); result.put("total", orders.getTotalElements());
        result.put("page", page); result.put("size", size);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/option/query")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "")
    public ResponseEntity<?> queryOptionOrders(@RequestBody Map<String, Object> params,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        int page = Math.max(0, Integer.parseInt(params.getOrDefault("page", 0).toString()));
        int size = Math.max(1, Math.min(100, Integer.parseInt(params.getOrDefault("size", 20).toString())));
        Page<OptionOrder> orders = optionOrderRepository.findAll(orderFilter(params, authHeader),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        List<Map<String, Object>> list = orders.getContent().stream().map(order -> {
            fillAgentInfo(order);
            Map<String, Object> item = convertOptionOrderToMap(order);
            userAccountRepository.findById(order.getUserId()).ifPresent(user -> item.put("userRemark", user.getRemark()));
            return item;
        }).collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("list", list); result.put("total", orders.getTotalElements());
        result.put("page", page); result.put("size", size);
        return ResponseEntity.ok(result);
    }

    /**
     * 将合约订单转换为Map并添加用户备注
     */
    private Map<String, Object> convertContractOrderToMap(ContractOrder order) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", order.getId());
        map.put("deleted", order.isDeleted());
        map.put("deletedAt", order.getDeletedAt());
        map.put("deletedBy", order.getDeletedBy());
        map.put("userId", order.getUserId());
        map.put("symbol", order.getSymbol());
        map.put("displayName", order.getDisplayName());
        map.put("side", order.getSide());
        map.put("type", order.getType());
        map.put("quantity", order.getQuantity());
        map.put("quantityUnitType", order.getQuantityUnitType());
        map.put("quantityAsset", order.getQuantityAsset());
        map.put("specVersion", order.getSpecVersion());
        map.put("openPrice", order.getOpenPrice());
        map.put("closePrice", order.getClosePrice());
        map.put("margin", order.getMargin());
        map.put("fee", order.getFee());
        map.put("profit", order.getProfit());
        map.put("status", order.getStatus());
        map.put("createdAt", order.getCreatedAt());
        map.put("closeTime", order.getCloseTime());
        map.put("openTime", order.getOpenTime());
        map.put("agentInfo", order.getAgentInfo());
        return map;
    }

    /**
     * 将期货订单转换为Map并添加用户备注
     */
    private Map<String, Object> convertOptionOrderToMap(OptionOrder order) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", order.getId());
        map.put("deleted", order.isDeleted());
        map.put("deletedAt", order.getDeletedAt());
        map.put("deletedBy", order.getDeletedBy());
        map.put("userId", order.getUserId());
        map.put("symbol", order.getSymbol());
        map.put("displayName", order.getDisplayName());
        map.put("direction", order.getDirection());
        map.put("amount", order.getAmount());
        map.put("openPrice", order.getOpenPrice());
        map.put("closePrice", order.getClosePrice());
        map.put("profit", order.getProfit());
        map.put("status", order.getStatus());
        map.put("duration", order.getDuration()); // 添加时长字段
        map.put("presetProfitType", order.getPresetProfitType());
        map.put("createdAt", order.getCreatedAt());
        map.put("closeTime", order.getCloseTime());
        map.put("openTime", order.getOpenTime());
        map.put("agentInfo", order.getAgentInfo());
        return map;
    }

    /**
     * 填充合约订单的代理信息
     */
    private void fillAgentInfo(ContractOrder order) {
        if (order.getUserId() == null) {
            return;
        }
        
        UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
        if (user == null) {
            return;
        }
        
        if (user.getParentUserId() != null) {
            UserAccount agent = userAccountRepository.findById(user.getParentUserId()).orElse(null);
            if (agent != null) {
                String agentName = agent.getNickname();
                if (agentName == null || agentName.isEmpty()) {
                    agentName = agent.getEmail();
                }
                order.setAgentInfo("所属代理:" + agentName);
            }
        }
    }

    /**
     * 填充期货订单的代理信息
     */
    private void fillAgentInfo(OptionOrder order) {
        if (order.getUserId() == null) {
            return;
        }
        
        UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
        if (user == null) {
            return;
        }
        
        if (user.getParentUserId() != null) {
            UserAccount agent = userAccountRepository.findById(user.getParentUserId()).orElse(null);
            if (agent != null) {
                String agentName = agent.getNickname();
                if (agentName == null || agentName.isEmpty()) {
                    agentName = agent.getEmail();
                }
                order.setAgentInfo("所属代理:" + agentName);
            }
        }
    }

    /**
     * 管理员平仓订单
     */
    @PostMapping("/contract/{orderId}/close")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "close_order")
    public ResponseEntity<?> adminCloseOrder(
            @PathVariable Long orderId,
            @RequestBody(required = false) Map<String, Object> req) {
        try {
            ContractOrder order = contractOrderService.adminCloseOrder(orderId, null);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "平仓成功");
            resp.put("order", order);

            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "平仓失败: " + (com.gtcfesk.exchange.common.SafeErrors.message(e) != null ? com.gtcfesk.exchange.common.SafeErrors.message(e) : "未知错误"));
            e.printStackTrace();
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 管理员撤单
     */
    @PostMapping("/contract/{orderId}/cancel")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "cancel_order")
    public ResponseEntity<?> adminCancelOrder(@PathVariable Long orderId) {
        try {
            ContractOrder order = contractOrderService.adminCancelOrder(orderId);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "撤单成功");
            resp.put("order", order);

            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "撤单失败: " + (com.gtcfesk.exchange.common.SafeErrors.message(e) != null ? com.gtcfesk.exchange.common.SafeErrors.message(e) : "未知错误"));
            e.printStackTrace();
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 设置期货订单的预设盈亏类型
     */
    @PostMapping("/option/{orderId}/preset-profit")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "")
    public ResponseEntity<?> setPresetProfitType(
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> req) {
        try {
            String presetType = (String) req.get("presetType"); // PROFIT 或 LOSS
            
            if (presetType == null || (!presetType.equals("PROFIT") && !presetType.equals("LOSS"))) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "预设类型必须是 PROFIT 或 LOSS");
                return ResponseEntity.badRequest().body(resp);
            }

            OptionOrder order = optionOrderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException("订单不存在"));

            if (!"TRADING".equals(order.getStatus())) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "只能设置交易中订单的预设盈亏");
                return ResponseEntity.badRequest().body(resp);
            }

            order.setPresetProfitType(presetType);
            OptionOrder saved = optionOrderRepository.save(order);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "设置成功");
            resp.put("order", saved);

            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "设置失败: " + (com.gtcfesk.exchange.common.SafeErrors.message(e) != null ? com.gtcfesk.exchange.common.SafeErrors.message(e) : "未知错误"));
            e.printStackTrace();
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 清除期货订单的预设盈亏类型
     */
    @PostMapping("/option/{orderId}/clear-preset")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "clear_preset")
    public ResponseEntity<?> clearPresetProfitType(@PathVariable Long orderId) {
        try {
            OptionOrder order = optionOrderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException("订单不存在"));

            order.setPresetProfitType(null);
            OptionOrder saved = optionOrderRepository.save(order);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "清除成功");
            resp.put("order", saved);

            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "清除失败: " + (com.gtcfesk.exchange.common.SafeErrors.message(e) != null ? com.gtcfesk.exchange.common.SafeErrors.message(e) : "未知错误"));
            e.printStackTrace();
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @DeleteMapping({"/contract/{orderId}", "/contract/{orderId}/abnormal-delete"})
    @org.springframework.transaction.annotation.Transactional
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "delete_order")
    public ResponseEntity<?> softDeleteContractOrder(@PathVariable Long orderId, org.springframework.security.core.Authentication auth) {
        ContractOrder order = contractOrderRepository.findById(orderId).orElseThrow(() -> new BusinessException("订单不存在"));
        if (!order.isDeleted()) {
            if (!java.util.Arrays.asList("CLOSED", "CANCELLED").contains(order.getStatus()))
                throw new BusinessException("请先平仓或撤单，再删除订单；删除不会结算或退还资金");
            // Update metadata only: do not invoke settlement-related entity callbacks.
            if (contractOrderRepository.updateDeletion(orderId, order.getRowVersion(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), auth.getName()) != 1)
                throw new BusinessException("订单已变化，请刷新后重试");
        }
        return ResponseEntity.ok(java.util.Collections.singletonMap("success", true));
    }

    @PostMapping("/contract/{orderId}/restore")
    @org.springframework.transaction.annotation.Transactional
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "restore_order")
    public ResponseEntity<?> restoreContractOrder(@PathVariable Long orderId) {
        ContractOrder order = contractOrderRepository.findById(orderId).orElseThrow(() -> new BusinessException("订单不存在"));
        if (order.isDeleted()) {
            // Update metadata only: do not invoke settlement-related entity callbacks.
            if (contractOrderRepository.updateDeletion(orderId, order.getRowVersion(), null, null) != 1)
                throw new BusinessException("订单已变化，请刷新后重试");
        }
        return ResponseEntity.ok(java.util.Collections.singletonMap("success", true));
    }

    @DeleteMapping({"/option/{orderId}", "/option/{orderId}/abnormal-delete"})
    @org.springframework.transaction.annotation.Transactional
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "delete_order")
    public ResponseEntity<?> softDeleteOptionOrder(@PathVariable Long orderId, org.springframework.security.core.Authentication auth) {
        OptionOrder order = optionOrderRepository.findById(orderId).orElseThrow(() -> new BusinessException("订单不存在"));
        if (!order.isDeleted()) {
            if (!java.util.Arrays.asList("CLOSED", "CANCELLED").contains(order.getStatus()))
                throw new BusinessException("请先平仓或撤单，再删除订单；删除不会结算或退还资金");
            // Update metadata only: do not invoke settlement-related entity callbacks.
            if (optionOrderRepository.updateDeletion(orderId, order.getRowVersion(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), auth.getName()) != 1)
                throw new BusinessException("订单已变化，请刷新后重试");
        }
        return ResponseEntity.ok(java.util.Collections.singletonMap("success", true));
    }

    @PostMapping("/option/{orderId}/restore")
    @org.springframework.transaction.annotation.Transactional
    @com.gtcfesk.exchange.config.AdminPermission(menu = "orders", action = "restore_order")
    public ResponseEntity<?> restoreOptionOrder(@PathVariable Long orderId) {
        OptionOrder order = optionOrderRepository.findById(orderId).orElseThrow(() -> new BusinessException("订单不存在"));
        if (order.isDeleted()) {
            // Update metadata only: do not invoke settlement-related entity callbacks.
            if (optionOrderRepository.updateDeletion(orderId, order.getRowVersion(), null, null) != 1)
                throw new BusinessException("订单已变化，请刷新后重试");
        }
        return ResponseEntity.ok(java.util.Collections.singletonMap("success", true));
    }

}
