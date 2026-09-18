package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.ContractOrderRepository;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContractOrderService {

    private final ContractOrderRepository contractOrderRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final TradingSymbolRepository tradingSymbolRepository;
    private final com.gtcfesk.exchange.market.ForexQuoteMarketService quotes;

    /**
     * 创建合约订单
     * 合约交易使用合约资产（CONTRACT）
     */
    @Transactional
    public ContractOrder createOrder(Long userId, CreateContractOrderRequest req) {
        // 获取交易对信息
        TradingSymbol symbol = tradingSymbolRepository.findBySymbol(req.getSymbol())
                .orElseThrow(() -> new BusinessException("交易对不存在"));
        
        // 获取合约设置
        BigDecimal lotSize = symbol.getLotSize() != null ? symbol.getLotSize() : BigDecimal.valueOf(1000);
        BigDecimal feeMultiplier = symbol.getFeeMultiplier() != null ? symbol.getFeeMultiplier() : BigDecimal.valueOf(30);
        BigDecimal leverage = symbol.getLeverage() != null ? symbol.getLeverage() : BigDecimal.valueOf(10);
        
        // 获取或创建合约资产账户
        AssetAccount contractAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "CONTRACT")
                .orElseGet(() -> {
                    AssetAccount newAccount = new AssetAccount();
                    newAccount.setUserId(userId);
                    newAccount.setCoin("CONTRACT");
                    newAccount.setAvailable(BigDecimal.ZERO);
                    newAccount.setFrozen(BigDecimal.ZERO);
                    return assetAccountRepository.save(newAccount);
                });

        // 计算预计保证金 = 买入数量 × 每手数量（不除以杠杆）
        BigDecimal contractValue = req.getQuantity().multiply(lotSize);
        BigDecimal requiredMargin = contractValue; // 预计保证金 = 数量 × 每手数量
        
        // 计算预计手续费 = 买入数量 × 手续费倍数
        BigDecimal fee = req.getQuantity().multiply(feeMultiplier);
        
        // 总费用 = 预计保证金 + 预计手续费
        BigDecimal totalCost = requiredMargin.add(fee);

        // 检查余额是否足够
        BigDecimal available = contractAccount.getAvailable() != null ? contractAccount.getAvailable() : BigDecimal.ZERO;
        if (available.compareTo(totalCost) < 0) {
            throw new BusinessException("合约资产余额不足，需要: " + totalCost + "，可用: " + available);
        }

        // 冻结总费用（保证金 + 手续费）
        contractAccount.setAvailable(available.subtract(totalCost));
        BigDecimal frozen = contractAccount.getFrozen() != null ? contractAccount.getFrozen() : BigDecimal.ZERO;
        contractAccount.setFrozen(frozen.add(totalCost));
        assetAccountRepository.save(contractAccount);

        // 创建订单
        ContractOrder order = new ContractOrder();
        order.setUserId(userId);
        order.setSymbol(req.getSymbol());
        order.setSide(req.getSide()); // BUY or SELL
        order.setType(req.getType()); // MARKET or LIMIT
        order.setQuantity(req.getQuantity());
        order.setPrice(req.getPrice());
        // 初始设置当前价，开仓价在下面根据类型设置
        order.setCurrentPrice(req.getCurrentPrice());
        order.setStopLoss(req.getStopLoss());
        order.setTakeProfit(req.getTakeProfit());
        order.setMargin(requiredMargin);
        order.setFee(fee); // 手续费
        order.setLeverage(leverage); // 杠杆倍数
        order.setProfit(BigDecimal.ZERO);
        
        // 如果是市价单，立即开仓；如果是限价单，状态为挂单
        if ("MARKET".equals(req.getType())) {
            order.setStatus("OPEN");
            order.setOpenTime(LocalDateTime.now());
            order.setOpenPrice(req.getCurrentPrice());
        } else {
            order.setStatus("PENDING");
            // 限价单的开仓价应该是挂单价
            order.setOpenPrice(req.getPrice());
        }

        return contractOrderRepository.save(order);
    }

    /**
     * 获取用户的合约订单列表
     */
    public List<ContractOrder> getUserOrders(Long userId, String status) {
        if (status != null && !status.isEmpty()) {
            return contractOrderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
        }
        return contractOrderRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 获取合约资产余额
     */
    public BigDecimal getContractBalance(Long userId) {
        AssetAccount contractAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "CONTRACT")
                .orElseGet(() -> {
                    // 如果账户不存在，自动创建
                    AssetAccount newAccount = new AssetAccount();
                    newAccount.setUserId(userId);
                    newAccount.setCoin("CONTRACT");
                    newAccount.setAvailable(BigDecimal.ZERO);
                    newAccount.setFrozen(BigDecimal.ZERO);
                    return assetAccountRepository.save(newAccount);
                });
        return contractAccount.getAvailable() != null ? contractAccount.getAvailable() : BigDecimal.ZERO;
    }

    /**
     * 平仓订单
     */
    @Transactional
    public ContractOrder closeOrder(Long userId, Long orderId, BigDecimal closePrice) {
        ContractOrder order = contractOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        // 检查订单是否属于该用户
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此订单");
        }

        // 检查订单状态
        if (!"OPEN".equals(order.getStatus())) {
            throw new BusinessException("只能平仓持仓中的订单");
        }

        // 计算盈亏（考虑杠杆倍数）
        BigDecimal profit = BigDecimal.ZERO;
        if (order.getOpenPrice() != null && closePrice != null) {
            // 获取杠杆倍数，如果没有则默认为1（无杠杆）
            BigDecimal leverage = order.getLeverage() != null ? order.getLeverage() : BigDecimal.ONE;
            
            // 计算价格差
            BigDecimal priceDiff;
            if ("BUY".equals(order.getSide())) {
                // 买入：价格差 = 平仓价 - 开仓价
                priceDiff = closePrice.subtract(order.getOpenPrice());
            } else {
                // 卖出：价格差 = 开仓价 - 平仓价
                priceDiff = order.getOpenPrice().subtract(closePrice);
            }
            
            // 盈亏 = 价格差 × 数量 × 杠杆倍数
            // 杠杆放大盈亏，杠杆越高，盈亏越大
            profit = priceDiff.multiply(order.getQuantity())
                    .multiply(leverage);
        }

        // 更新订单状态
        order.setStatus("CLOSED");
        order.setClosePrice(closePrice);
        order.setCurrentPrice(closePrice);
        order.setProfit(profit);
        order.setCloseTime(LocalDateTime.now());

        // 获取合约资产账户
        AssetAccount contractAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));

        // 解冻保证金和手续费
        BigDecimal totalFrozen = order.getMargin().add(order.getFee());
        BigDecimal frozen = contractAccount.getFrozen() != null ? contractAccount.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(totalFrozen) < 0) {
            throw new BusinessException("冻结金额不足");
        }
        contractAccount.setFrozen(frozen.subtract(totalFrozen));

        // 返还保证金和手续费，加上盈亏
        BigDecimal available = contractAccount.getAvailable() != null ? contractAccount.getAvailable() : BigDecimal.ZERO;
        contractAccount.setAvailable(available.add(totalFrozen).add(profit));
        assetAccountRepository.save(contractAccount);

        return contractOrderRepository.save(order);
    }

    /**
     * 管理员平仓订单
     */
    @Transactional
    public ContractOrder adminCloseOrder(Long orderId, BigDecimal closePrice) {
        ContractOrder order = contractOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        // 检查订单状态
        if (!"OPEN".equals(order.getStatus())) {
            throw new BusinessException("只能平仓持仓中的订单");
        }

        Long userId = order.getUserId();

        // 计算盈亏（考虑杠杆倍数）
        BigDecimal profit = BigDecimal.ZERO;
        if (order.getOpenPrice() != null && closePrice != null) {
            // 获取杠杆倍数，如果没有则默认为1（无杠杆）
            BigDecimal leverage = order.getLeverage() != null ? order.getLeverage() : BigDecimal.ONE;
            
            // 计算价格差
            BigDecimal priceDiff;
            if ("BUY".equals(order.getSide())) {
                // 买入：价格差 = 平仓价 - 开仓价
                priceDiff = closePrice.subtract(order.getOpenPrice());
            } else {
                // 卖出：价格差 = 开仓价 - 平仓价
                priceDiff = order.getOpenPrice().subtract(closePrice);
            }
            
            // 盈亏 = 价格差 × 数量 × 杠杆倍数
            // 杠杆放大盈亏，杠杆越高，盈亏越大
            profit = priceDiff.multiply(order.getQuantity())
                    .multiply(leverage);
        }

        // 更新订单状态
        order.setStatus("CLOSED");
        order.setClosePrice(closePrice);
        order.setCurrentPrice(closePrice);
        order.setProfit(profit);
        order.setCloseTime(LocalDateTime.now());

        // 获取合约资产账户
        AssetAccount contractAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));

        // 解冻保证金和手续费
        BigDecimal totalFrozen = order.getMargin().add(order.getFee());
        BigDecimal frozen = contractAccount.getFrozen() != null ? contractAccount.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(totalFrozen) < 0) {
            throw new BusinessException("冻结金额不足");
        }
        contractAccount.setFrozen(frozen.subtract(totalFrozen));

        // 返还保证金和手续费，加上盈亏
        BigDecimal available = contractAccount.getAvailable() != null ? contractAccount.getAvailable() : BigDecimal.ZERO;
        contractAccount.setAvailable(available.add(totalFrozen).add(profit));
        assetAccountRepository.save(contractAccount);

        return contractOrderRepository.save(order);
    }

    /**
     * 撤单（取消挂单）
     */
    @Transactional
    public ContractOrder cancelOrder(Long userId, Long orderId) {
        ContractOrder order = contractOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        // 检查订单是否属于该用户
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此订单");
        }

        // 检查订单状态
        if (!"PENDING".equals(order.getStatus())) {
            throw new BusinessException("只能取消挂单中的订单");
        }

        // 更新订单状态
        order.setStatus("CANCELLED");

        // 获取合约资产账户
        AssetAccount contractAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));

        // 解冻保证金和手续费
        BigDecimal totalFrozen = order.getMargin().add(order.getFee());
        BigDecimal frozen = contractAccount.getFrozen() != null ? contractAccount.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(totalFrozen) < 0) {
            throw new BusinessException("冻结金额不足");
        }
        contractAccount.setFrozen(frozen.subtract(totalFrozen));

        // 返还保证金和手续费
        BigDecimal available = contractAccount.getAvailable() != null ? contractAccount.getAvailable() : BigDecimal.ZERO;
        contractAccount.setAvailable(available.add(totalFrozen));
        assetAccountRepository.save(contractAccount);

        return contractOrderRepository.save(order);
    }

    /**
     * 管理员撤单
     */
    @Transactional
    public ContractOrder adminCancelOrder(Long orderId) {
        ContractOrder order = contractOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        // 检查订单状态
        if (!"PENDING".equals(order.getStatus())) {
            throw new BusinessException("只能取消挂单中的订单");
        }

        Long userId = order.getUserId();

        // 更新订单状态
        order.setStatus("CANCELLED");

        // 获取合约资产账户
        AssetAccount contractAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));

        // 解冻保证金和手续费
        BigDecimal totalFrozen = order.getMargin().add(order.getFee());
        BigDecimal frozen = contractAccount.getFrozen() != null ? contractAccount.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(totalFrozen) < 0) {
            throw new BusinessException("冻结金额不足");
        }
        contractAccount.setFrozen(frozen.subtract(totalFrozen));

        // 返还保证金和手续费
        BigDecimal available = contractAccount.getAvailable() != null ? contractAccount.getAvailable() : BigDecimal.ZERO;
        contractAccount.setAvailable(available.add(totalFrozen));
        assetAccountRepository.save(contractAccount);

        return contractOrderRepository.save(order);
    }

    /**
     * 更新订单的止盈止损
     */
    @Transactional
    public ContractOrder updateStopLossTakeProfit(Long userId, Long orderId, BigDecimal stopLoss, BigDecimal takeProfit) {
        ContractOrder order = contractOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        // 检查订单是否属于该用户
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此订单");
        }

        // 检查订单状态
        if (!"OPEN".equals(order.getStatus())) {
            throw new BusinessException("只能修改持仓中订单的止盈止损");
        }

        // 更新止盈止损
        order.setStopLoss(stopLoss);
        order.setTakeProfit(takeProfit);

        return contractOrderRepository.save(order);
    }
    
    /**
     * 检查并自动平仓触发止盈止损的订单
     * @param symbol 交易对符号，如果为null则检查所有交易对
     * @param currentPrice 当前价格
     */
    @Transactional
    public void checkAndAutoCloseSnapshotOrders() {
        for (String symbol : quotes.freshPrices().keySet()) checkAndAutoCloseOrders(symbol, quotes.freshPrice(symbol));
    }

    @Transactional
    public void checkAndAutoCloseOrders(String symbol, BigDecimal currentPrice) {
        currentPrice = quotes.freshPrice(symbol);
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        
        // 获取需要检查的订单列表
        List<ContractOrder> ordersToCheck;
        if (symbol != null && !symbol.isEmpty()) {
            ordersToCheck = contractOrderRepository.findBySymbolAndStatus(symbol, "OPEN");
        } else {
            ordersToCheck = contractOrderRepository.findByStatus("OPEN");
        }
        
        for (ContractOrder order : ordersToCheck) {
            try {
                // 更新订单的当前价格
                order.setCurrentPrice(currentPrice);
                
                // 检查是否触发止盈或止损
                boolean shouldClose = false;
                BigDecimal closePrice = currentPrice;
                
                // 检查止损
                if (order.getStopLoss() != null && order.getStopLoss().compareTo(BigDecimal.ZERO) > 0) {
                    if ("BUY".equals(order.getSide())) {
                        // 买入订单：当前价 <= 止损价，触发止损
                        if (currentPrice.compareTo(order.getStopLoss()) <= 0) {
                            shouldClose = true;
                            closePrice = order.getStopLoss(); // 使用止损价平仓
                        }
                    } else {
                        // 卖出订单：当前价 >= 止损价，触发止损
                        if (currentPrice.compareTo(order.getStopLoss()) >= 0) {
                            shouldClose = true;
                            closePrice = order.getStopLoss(); // 使用止损价平仓
                        }
                    }
                }
                
                // 检查止盈（如果还没触发止损）
                if (!shouldClose && order.getTakeProfit() != null && order.getTakeProfit().compareTo(BigDecimal.ZERO) > 0) {
                    if ("BUY".equals(order.getSide())) {
                        // 买入订单：当前价 >= 止盈价，触发止盈
                        if (currentPrice.compareTo(order.getTakeProfit()) >= 0) {
                            shouldClose = true;
                            closePrice = order.getTakeProfit(); // 使用止盈价平仓
                        }
                    } else {
                        // 卖出订单：当前价 <= 止盈价，触发止盈
                        if (currentPrice.compareTo(order.getTakeProfit()) <= 0) {
                            shouldClose = true;
                            closePrice = order.getTakeProfit(); // 使用止盈价平仓
                        }
                    }
                }
                
                // 如果触发止盈或止损，自动平仓
                if (shouldClose) {
                    adminCloseOrder(order.getId(), closePrice);
                }
            } catch (Exception e) {
                // 静默处理异常，避免日志输出
            }
        }
    }
    
    /**
     * 检查并强制平仓：当合约所有订单的总盈亏亏损大于（合约账户余额+保证金）时，自动强制平仓
     * @param symbolPriceMap 交易对符号到当前价格的映射，如果为null则只检查已更新currentPrice的订单
     */
    @Transactional
    public void checkAndForceCloseOrders(Map<String, BigDecimal> symbolPriceMap) {
        symbolPriceMap = quotes.freshPrices();
        // 获取所有持仓订单
        List<ContractOrder> openOrders = contractOrderRepository.findByStatus("OPEN");
        if (openOrders.isEmpty()) {
            return;
        }
        
        // 按用户分组
        Map<Long, List<ContractOrder>> ordersByUser = openOrders.stream()
                .collect(Collectors.groupingBy(ContractOrder::getUserId));
        
        // 对每个用户检查强制平仓条件
        for (Map.Entry<Long, List<ContractOrder>> entry : ordersByUser.entrySet()) {
            Long userId = entry.getKey();
            List<ContractOrder> userOrders = entry.getValue();
            // An account-level calculation requires every open position, using current valid snapshots only.
            boolean complete = true;
            for (ContractOrder order : userOrders) {
                BigDecimal fresh = quotes.freshPrice(order.getSymbol());
                if (fresh == null) { complete = false; break; }
                symbolPriceMap.put(order.getSymbol(), fresh);
            }
            if (!complete) continue;
            
            try {
                // 获取合约资产账户
                AssetAccount contractAccount = assetAccountRepository
                        .findByUserIdAndCoin(userId, "CONTRACT")
                        .orElse(null);
                
                if (contractAccount == null) {
                    continue;
                }
                
                BigDecimal available = contractAccount.getAvailable() != null 
                        ? contractAccount.getAvailable() 
                        : BigDecimal.ZERO;
                
                // 计算所有订单的保证金总和（包括已冻结的）
                BigDecimal totalMargin = BigDecimal.ZERO;
                BigDecimal totalFee = BigDecimal.ZERO;
                BigDecimal totalProfit = BigDecimal.ZERO;
                
                // 计算每个订单的实时盈亏
                for (ContractOrder order : userOrders) {
                    // 获取当前价格
                    BigDecimal currentPrice = symbolPriceMap.get(order.getSymbol());
                    order.setCurrentPrice(currentPrice);

                    // 累加保证金和手续费
                    if (order.getMargin() != null) {
                        totalMargin = totalMargin.add(order.getMargin());
                    }
                    if (order.getFee() != null) {
                        totalFee = totalFee.add(order.getFee());
                    }
                    
                    // 计算实时盈亏
                    BigDecimal profit = calculateProfit(order, currentPrice);
                    totalProfit = totalProfit.add(profit);
                }
                
                // 总保证金 = 保证金 + 手续费
                BigDecimal totalMarginAndFee = totalMargin.add(totalFee);
                
                // 检查强制平仓条件：总亏损 > (账户余额 + 保证金总和)
                // 总亏损 = -totalProfit（如果totalProfit为负数）
                if (totalProfit.compareTo(BigDecimal.ZERO) < 0) {
                    BigDecimal totalLoss = totalProfit.negate(); // 转换为正数（亏损金额）
                    BigDecimal availablePlusMargin = available.add(totalMarginAndFee);
                    
                    // 如果总亏损 > (余额 + 保证金)，强制平仓所有订单
                    if (totalLoss.compareTo(availablePlusMargin) > 0) {
                        if (userOrders.stream().anyMatch(order -> quotes.freshPrice(order.getSymbol()) == null)) continue;
                        // 强制平仓该用户的所有订单
                        for (ContractOrder order : userOrders) {
                            try {
                                BigDecimal closePrice = symbolPriceMap.get(order.getSymbol());
                                if (closePrice != null && closePrice.compareTo(BigDecimal.ZERO) > 0) {
                                    adminCloseOrder(order.getId(), closePrice);
                                }
                            } catch (Exception e) {
                                // 静默处理单个订单平仓失败
                            }
                        }
                        
                        // 强制平仓后，将合约账户余额设为0
                        contractAccount.setAvailable(BigDecimal.ZERO);
                        contractAccount.setFrozen(BigDecimal.ZERO);
                        assetAccountRepository.save(contractAccount);
                    }
                }
            } catch (Exception e) {
                // 静默处理异常，避免日志输出
            }
        }
    }
    
    /**
     * 计算订单的实时盈亏
     */
    private BigDecimal calculateProfit(ContractOrder order, BigDecimal currentPrice) {
        if (order.getOpenPrice() == null || currentPrice == null 
                || order.getOpenPrice().compareTo(BigDecimal.ZERO) <= 0 
                || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        // 获取杠杆倍数
        BigDecimal leverage = order.getLeverage() != null ? order.getLeverage() : BigDecimal.ONE;
        
        // 计算价格差
        BigDecimal priceDiff;
        if ("BUY".equals(order.getSide())) {
            // 买入：价格差 = 当前价 - 开仓价
            priceDiff = currentPrice.subtract(order.getOpenPrice());
        } else {
            // 卖出：价格差 = 开仓价 - 当前价
            priceDiff = order.getOpenPrice().subtract(currentPrice);
        }
        
        // 盈亏 = 价格差 × 数量 × 杠杆倍数
        return priceDiff.multiply(order.getQuantity()).multiply(leverage);
    }
}


