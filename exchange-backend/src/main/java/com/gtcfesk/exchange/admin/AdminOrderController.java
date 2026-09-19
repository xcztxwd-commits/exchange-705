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

    /**
     * 查询合约订单
     */
    @PostMapping("/contract/query")
    public ResponseEntity<?> queryContractOrders(
            @RequestBody Map<String, Object> params,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Long agentId = extractAgentId(authHeader);
            String userIdStr = (String) params.get("userId");
            String status = (String) params.get("status");
            Long filterAgentId = params.get("filterAgentId") != null ? Long.parseLong(params.get("filterAgentId").toString()) : null; // 管理员筛选代理ID
            Integer page = params.get("page") != null ? (Integer) params.get("page") : 0;
            Integer size = params.get("size") != null ? (Integer) params.get("size") : 20;

            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<ContractOrder> orderPage;

            // 如果是代理，只查询下级用户的订单
            if (agentId != null) {
                List<UserAccount> subordinates = userAccountRepository.findByParentUserId(agentId);
                Set<Long> subordinateUserIds = subordinates.stream()
                        .map(UserAccount::getId)
                        .collect(Collectors.toSet());
                
                if (subordinateUserIds.isEmpty()) {
                    // 没有下级用户，返回空列表
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("list", new java.util.ArrayList<>());
                    resp.put("total", 0L);
                    resp.put("page", page);
                    resp.put("size", size);
                    return ResponseEntity.ok(resp);
                }
                
                // 如果指定了用户ID，验证是否属于下级用户
                if (userIdStr != null && !userIdStr.isEmpty()) {
                    Long userId = Long.parseLong(userIdStr);
                    if (!subordinateUserIds.contains(userId)) {
                        // 不是下级用户，返回空列表
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("list", new java.util.ArrayList<>());
                        resp.put("total", 0L);
                        resp.put("page", page);
                        resp.put("size", size);
                        return ResponseEntity.ok(resp);
                    }
                    // 是下级用户，继续查询
                    if (status != null && !status.isEmpty()) {
                        orderPage = contractOrderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
                    } else {
                        orderPage = contractOrderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
                    }
                    // 填充代理信息和用户备注
                    List<ContractOrder> orders = orderPage.getContent();
                    List<Map<String, Object>> orderList = orders.stream().map(order -> {
                        fillAgentInfo(order);
                        Map<String, Object> orderMap = convertContractOrderToMap(order);
                        // 添加用户备注
                        if (order.getUserId() != null) {
                            UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
                            if (user != null) {
                                orderMap.put("userRemark", user.getRemark());
                            }
                        }
                        return orderMap;
                    }).collect(Collectors.toList());
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("list", orderList);
                    resp.put("total", orderPage.getTotalElements());
                    resp.put("page", orderPage.getNumber());
                    resp.put("size", orderPage.getSize());
                    return ResponseEntity.ok(resp);
                } else {
                    // 查询所有下级用户的订单
                    List<ContractOrder> allOrders = contractOrderRepository.findAll().stream()
                            .filter(order -> subordinateUserIds.contains(order.getUserId()))
                            .filter(order -> status == null || status.isEmpty() || status.equals(order.getStatus()))
                            .sorted((a, b) -> {
                                if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                                return b.getCreatedAt().compareTo(a.getCreatedAt());
                            })
                            .collect(Collectors.toList());
                    
                    // 手动分页
                    int start = page * size;
                    int end = Math.min(start + size, allOrders.size());
                    List<ContractOrder> pagedOrders = start < allOrders.size() 
                            ? allOrders.subList(start, end) 
                            : new java.util.ArrayList<>();
                    
                    // 转换为Map并添加用户备注
                    List<Map<String, Object>> orderList = pagedOrders.stream().map(order -> {
                        fillAgentInfo(order);
                        Map<String, Object> orderMap = convertContractOrderToMap(order);
                        if (order.getUserId() != null) {
                            UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
                            if (user != null) {
                                orderMap.put("userRemark", user.getRemark());
                            }
                        }
                        return orderMap;
                    }).collect(Collectors.toList());
                    
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("list", orderList);
                    resp.put("total", (long) allOrders.size());
                    resp.put("page", page);
                    resp.put("size", size);
                    return ResponseEntity.ok(resp);
                }
            } else {
                // 管理员查询所有订单
                if (filterAgentId != null) {
                    // 管理员按代理筛选
                    List<UserAccount> subordinates = userAccountRepository.findByParentUserId(filterAgentId);
                    Set<Long> subordinateUserIds = subordinates.stream()
                            .map(UserAccount::getId)
                            .collect(Collectors.toSet());
                    
                    if (subordinateUserIds.isEmpty()) {
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("list", new java.util.ArrayList<>());
                        resp.put("total", 0L);
                        resp.put("page", page);
                        resp.put("size", size);
                        return ResponseEntity.ok(resp);
                    }
                    
                    List<ContractOrder> allOrders = contractOrderRepository.findAll().stream()
                            .filter(order -> subordinateUserIds.contains(order.getUserId()))
                            .filter(order -> userIdStr == null || userIdStr.isEmpty() || order.getUserId().toString().equals(userIdStr))
                            .filter(order -> status == null || status.isEmpty() || status.equals(order.getStatus()))
                            .sorted((a, b) -> {
                                if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                                return b.getCreatedAt().compareTo(a.getCreatedAt());
                            })
                            .collect(Collectors.toList());
                    
                    int start = page * size;
                    int end = Math.min(start + size, allOrders.size());
                    List<ContractOrder> pagedOrders = start < allOrders.size() 
                            ? allOrders.subList(start, end) 
                            : new java.util.ArrayList<>();
                    
                    // 转换为Map并添加用户备注
                    List<Map<String, Object>> orderList = pagedOrders.stream().map(order -> {
                        fillAgentInfo(order);
                        Map<String, Object> orderMap = convertContractOrderToMap(order);
                        if (order.getUserId() != null) {
                            UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
                            if (user != null) {
                                orderMap.put("userRemark", user.getRemark());
                            }
                        }
                        return orderMap;
                    }).collect(Collectors.toList());
                    
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("list", orderList);
                    resp.put("total", (long) allOrders.size());
                    resp.put("page", page);
                    resp.put("size", size);
                    return ResponseEntity.ok(resp);
                } else if (userIdStr != null && !userIdStr.isEmpty()) {
                    Long userId = Long.parseLong(userIdStr);
                    if (status != null && !status.isEmpty()) {
                        orderPage = contractOrderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
                    } else {
                        orderPage = contractOrderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
                    }
                } else {
                    orderPage = contractOrderRepository.findAllByOrderByCreatedAtDesc(pageable);
                }
            }

            // 转换为Map并添加用户备注
            List<ContractOrder> orders = orderPage.getContent();
            List<Map<String, Object>> orderList = orders.stream().map(order -> {
                fillAgentInfo(order);
                Map<String, Object> orderMap = convertContractOrderToMap(order);
                if (order.getUserId() != null) {
                    UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
                    if (user != null) {
                        orderMap.put("userRemark", user.getRemark());
                    }
                }
                return orderMap;
            }).collect(Collectors.toList());

            Map<String, Object> resp = new HashMap<>();
            resp.put("list", orderList);
            resp.put("total", orderPage.getTotalElements());
            resp.put("page", orderPage.getNumber());
            resp.put("size", orderPage.getSize());

            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "查询失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 查询期货订单
     */
    @PostMapping("/option/query")
    public ResponseEntity<?> queryOptionOrders(
            @RequestBody Map<String, Object> params,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Long agentId = extractAgentId(authHeader);
            String userIdStr = (String) params.get("userId");
            String status = (String) params.get("status");
            Long filterAgentId = params.get("filterAgentId") != null ? Long.parseLong(params.get("filterAgentId").toString()) : null; // 管理员筛选代理ID
            Integer page = params.get("page") != null ? (Integer) params.get("page") : 0;
            Integer size = params.get("size") != null ? (Integer) params.get("size") : 20;

            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<OptionOrder> orderPage;

            // 如果是代理，只查询下级用户的订单
            if (agentId != null) {
                List<UserAccount> subordinates = userAccountRepository.findByParentUserId(agentId);
                Set<Long> subordinateUserIds = subordinates.stream()
                        .map(UserAccount::getId)
                        .collect(Collectors.toSet());
                
                if (subordinateUserIds.isEmpty()) {
                    // 没有下级用户，返回空列表
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("list", new java.util.ArrayList<>());
                    resp.put("total", 0L);
                    resp.put("page", page);
                    resp.put("size", size);
                    return ResponseEntity.ok(resp);
                }
                
                // 如果指定了用户ID，验证是否属于下级用户
                if (userIdStr != null && !userIdStr.isEmpty()) {
                    Long userId = Long.parseLong(userIdStr);
                    if (!subordinateUserIds.contains(userId)) {
                        // 不是下级用户，返回空列表
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("list", new java.util.ArrayList<>());
                        resp.put("total", 0L);
                        resp.put("page", page);
                        resp.put("size", size);
                        return ResponseEntity.ok(resp);
                    }
                    // 是下级用户，继续查询
                    if (status != null && !status.isEmpty()) {
                        orderPage = optionOrderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
                    } else {
                        orderPage = optionOrderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
                    }
                    // 转换为Map并添加用户备注
                    List<OptionOrder> orders = orderPage.getContent();
                    List<Map<String, Object>> orderList = orders.stream().map(order -> {
                        fillAgentInfo(order);
                        Map<String, Object> orderMap = convertOptionOrderToMap(order);
                        if (order.getUserId() != null) {
                            UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
                            if (user != null) {
                                orderMap.put("userRemark", user.getRemark());
                            }
                        }
                        return orderMap;
                    }).collect(Collectors.toList());
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("list", orderList);
                    resp.put("total", orderPage.getTotalElements());
                    resp.put("page", orderPage.getNumber());
                    resp.put("size", orderPage.getSize());
                    return ResponseEntity.ok(resp);
                } else {
                    // 查询所有下级用户的订单
                    List<OptionOrder> allOrders = optionOrderRepository.findAll().stream()
                            .filter(order -> subordinateUserIds.contains(order.getUserId()))
                            .filter(order -> status == null || status.isEmpty() || status.equals(order.getStatus()))
                            .sorted((a, b) -> {
                                if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                                return b.getCreatedAt().compareTo(a.getCreatedAt());
                            })
                            .collect(Collectors.toList());
                    
                    // 手动分页
                    int start = page * size;
                    int end = Math.min(start + size, allOrders.size());
                    List<OptionOrder> pagedOrders = start < allOrders.size() 
                            ? allOrders.subList(start, end) 
                            : new java.util.ArrayList<>();
                    
                    // 转换为Map并添加用户备注
                    List<Map<String, Object>> orderList = pagedOrders.stream().map(order -> {
                        fillAgentInfo(order);
                        Map<String, Object> orderMap = convertOptionOrderToMap(order);
                        if (order.getUserId() != null) {
                            UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
                            if (user != null) {
                                orderMap.put("userRemark", user.getRemark());
                            }
                        }
                        return orderMap;
                    }).collect(Collectors.toList());
                    
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("list", orderList);
                    resp.put("total", (long) allOrders.size());
                    resp.put("page", page);
                    resp.put("size", size);
                    return ResponseEntity.ok(resp);
                }
            } else {
                // 管理员查询所有订单
                if (filterAgentId != null) {
                    // 管理员按代理筛选
                    List<UserAccount> subordinates = userAccountRepository.findByParentUserId(filterAgentId);
                    Set<Long> subordinateUserIds = subordinates.stream()
                            .map(UserAccount::getId)
                            .collect(Collectors.toSet());
                    
                    if (subordinateUserIds.isEmpty()) {
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("list", new java.util.ArrayList<>());
                        resp.put("total", 0L);
                        resp.put("page", page);
                        resp.put("size", size);
                        return ResponseEntity.ok(resp);
                    }
                    
                    List<OptionOrder> allOrders = optionOrderRepository.findAll().stream()
                            .filter(order -> subordinateUserIds.contains(order.getUserId()))
                            .filter(order -> userIdStr == null || userIdStr.isEmpty() || order.getUserId().toString().equals(userIdStr))
                            .filter(order -> status == null || status.isEmpty() || status.equals(order.getStatus()))
                            .sorted((a, b) -> {
                                if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                                return b.getCreatedAt().compareTo(a.getCreatedAt());
                            })
                            .collect(Collectors.toList());
                    
                    int start = page * size;
                    int end = Math.min(start + size, allOrders.size());
                    List<OptionOrder> pagedOrders = start < allOrders.size() 
                            ? allOrders.subList(start, end) 
                            : new java.util.ArrayList<>();
                    
                    // 转换为Map并添加用户备注
                    List<Map<String, Object>> orderList = pagedOrders.stream().map(order -> {
                        fillAgentInfo(order);
                        Map<String, Object> orderMap = convertOptionOrderToMap(order);
                        if (order.getUserId() != null) {
                            UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
                            if (user != null) {
                                orderMap.put("userRemark", user.getRemark());
                            }
                        }
                        return orderMap;
                    }).collect(Collectors.toList());
                    
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("list", orderList);
                    resp.put("total", (long) allOrders.size());
                    resp.put("page", page);
                    resp.put("size", size);
                    return ResponseEntity.ok(resp);
                } else if (userIdStr != null && !userIdStr.isEmpty()) {
                    Long userId = Long.parseLong(userIdStr);
                    if (status != null && !status.isEmpty()) {
                        orderPage = optionOrderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
                    } else {
                        orderPage = optionOrderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
                    }
                } else {
                    orderPage = optionOrderRepository.findAllByOrderByCreatedAtDesc(pageable);
                }
            }

            // 转换为Map并添加用户备注
            List<OptionOrder> orders = orderPage.getContent();
            List<Map<String, Object>> orderList = orders.stream().map(order -> {
                fillAgentInfo(order);
                Map<String, Object> orderMap = convertOptionOrderToMap(order);
                if (order.getUserId() != null) {
                    UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
                    if (user != null) {
                        orderMap.put("userRemark", user.getRemark());
                    }
                }
                return orderMap;
            }).collect(Collectors.toList());

            Map<String, Object> resp = new HashMap<>();
            resp.put("list", orderList);
            resp.put("total", orderPage.getTotalElements());
            resp.put("page", orderPage.getNumber());
            resp.put("size", orderPage.getSize());

            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "查询失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 将合约订单转换为Map并添加用户备注
     */
    private Map<String, Object> convertContractOrderToMap(ContractOrder order) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", order.getId());
        map.put("userId", order.getUserId());
        map.put("symbol", order.getSymbol());
        map.put("side", order.getSide());
        map.put("type", order.getType());
        map.put("quantity", order.getQuantity());
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
        map.put("userId", order.getUserId());
        map.put("symbol", order.getSymbol());
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

    /**
     * 异常处理 - 删除合约订单（同时删除用户订单记录）
     */
    @DeleteMapping("/contract/{orderId}/abnormal-delete")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> abnormalDeleteContractOrder(@PathVariable Long orderId) {
        try {
            ContractOrder order = contractOrderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException("订单不存在"));

            // 检查订单状态，只有 OPEN 状态的订单才能异常处理删除
            if (!"OPEN".equals(order.getStatus())) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "只能删除持仓中的异常订单");
                return ResponseEntity.badRequest().body(resp);
            }

            Long userId = order.getUserId();

            // 如果订单有冻结的保证金和手续费，需要解冻并返还
            if (order.getMargin() != null && order.getFee() != null) {
                try {
                    AssetAccount contractAccount = assetAccountRepository
                        .findByUserIdAndCoin(userId, "CONTRACT")
                        .orElse(null);
                    
                    if (contractAccount != null) {
                        BigDecimal totalFrozen = order.getMargin().add(order.getFee());
                        BigDecimal frozen = contractAccount.getFrozen() != null 
                            ? contractAccount.getFrozen() 
                            : BigDecimal.ZERO;
                        
                        if (frozen.compareTo(totalFrozen) >= 0) {
                            contractAccount.setFrozen(frozen.subtract(totalFrozen));
                            BigDecimal available = contractAccount.getAvailable() != null 
                                ? contractAccount.getAvailable() 
                                : BigDecimal.ZERO;
                            contractAccount.setAvailable(available.add(totalFrozen));
                            assetAccountRepository.save(contractAccount);
                        }
                    }
                } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
                    // 如果资产账户不存在或处理失败，记录日志但继续删除订单
                    System.out.println("[AdminOrderController] 处理资产账户失败: " + e.getMessage());
                }
            }

            // 删除订单
            contractOrderRepository.deleteById(orderId);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "订单删除成功");

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
            resp.put("message", "删除失败: " + (com.gtcfesk.exchange.common.SafeErrors.message(e) != null ? com.gtcfesk.exchange.common.SafeErrors.message(e) : "未知错误"));
            e.printStackTrace();
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 异常处理 - 删除期货订单（同时删除用户订单记录）
     */
    @DeleteMapping("/option/{orderId}/abnormal-delete")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> abnormalDeleteOptionOrder(@PathVariable Long orderId) {
        try {
            OptionOrder order = optionOrderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException("订单不存在"));

            // 检查订单状态，只有 TRADING 状态的订单才能异常处理删除
            if (!"TRADING".equals(order.getStatus())) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "只能删除交易中的异常订单");
                return ResponseEntity.badRequest().body(resp);
            }

            Long userId = order.getUserId();

            // 如果订单有投入的金额，需要解冻并返还到期权账户
            if (order.getAmount() != null && order.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                try {
                    AssetAccount optionAccount = assetAccountRepository
                        .findByUserIdAndCoin(userId, "OPTION")
                        .orElse(null);
                    
                    if (optionAccount != null) {
                        BigDecimal amount = order.getAmount();
                        BigDecimal frozen = optionAccount.getFrozen() != null 
                            ? optionAccount.getFrozen() 
                            : BigDecimal.ZERO;
                        
                        // 解冻金额（如果冻结金额足够）
                        if (frozen.compareTo(amount) >= 0) {
                            optionAccount.setFrozen(frozen.subtract(amount));
                            BigDecimal available = optionAccount.getAvailable() != null 
                                ? optionAccount.getAvailable() 
                                : BigDecimal.ZERO;
                            // 返还投入的金额到可用余额
                            optionAccount.setAvailable(available.add(amount));
                            assetAccountRepository.save(optionAccount);
                            System.out.println("[AdminOrderController] ✅ 期权订单异常处理：已返还金额 " + amount + " 到用户 " + userId + " 的期权账户");
                        } else {
                            // 如果冻结金额不足，仍然尝试返还（可能是数据不一致的情况）
                            System.out.println("[AdminOrderController] ⚠️ 期权订单异常处理：冻结金额不足，冻结=" + frozen + "，订单金额=" + amount);
                            BigDecimal available = optionAccount.getAvailable() != null 
                                ? optionAccount.getAvailable() 
                                : BigDecimal.ZERO;
                            // 将冻结金额全部解冻，并返还订单金额
                            optionAccount.setFrozen(BigDecimal.ZERO);
                            optionAccount.setAvailable(available.add(amount));
                            assetAccountRepository.save(optionAccount);
                            System.out.println("[AdminOrderController] ✅ 期权订单异常处理：已强制返还金额 " + amount + " 到用户 " + userId + " 的期权账户");
                        }
                    } else {
                        System.out.println("[AdminOrderController] ⚠️ 期权订单异常处理：用户 " + userId + " 的期权账户不存在，无法返还金额");
                    }
                } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
                    // 如果资产账户不存在或处理失败，记录日志但继续删除订单
                    System.err.println("[AdminOrderController] ⚠️ 期权订单异常处理：处理资产账户失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            // 删除订单
            optionOrderRepository.deleteById(orderId);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "订单删除成功，投入金额已自动返还");

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
            resp.put("message", "删除失败: " + (com.gtcfesk.exchange.common.SafeErrors.message(e) != null ? com.gtcfesk.exchange.common.SafeErrors.message(e) : "未知错误"));
            e.printStackTrace();
            return ResponseEntity.badRequest().body(resp);
        }
    }
}

