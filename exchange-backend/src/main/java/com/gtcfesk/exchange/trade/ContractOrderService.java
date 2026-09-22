package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.TradeValidation;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final PlatformTransactionManager transactionManager;
    private final com.gtcfesk.exchange.market.MarketCategoryService categories;

    /**
     * 创建合约订单
     * 合约交易使用合约资产（CONTRACT）
     */
    @Transactional
    public ContractOrder createOrder(Long userId, CreateContractOrderRequest req) {
        if (req == null || req.getSymbol() == null || req.getSymbol().trim().isEmpty()
                || !("BUY".equals(req.getSide()) || "SELL".equals(req.getSide()))
                || !("MARKET".equals(req.getType()) || "LIMIT".equals(req.getType()))) {
            throw new BusinessException("交易品种、方向或订单类型无效");
        }
        com.gtcfesk.exchange.common.TradeValidation.positive(req.getQuantity(), "数量");
        if ("LIMIT".equals(req.getType())) com.gtcfesk.exchange.common.TradeValidation.positive(req.getPrice(), "限价");
        com.gtcfesk.exchange.common.TradeValidation.optionalPositive(req.getStopLoss(), "止损价格");
        com.gtcfesk.exchange.common.TradeValidation.optionalPositive(req.getTakeProfit(), "止盈价格");
        // 获取交易对信息
        TradingSymbol symbol = tradingSymbolRepository.findBySymbol(req.getSymbol())
                .orElseThrow(() -> new BusinessException("交易对不存在"));
        
        if (!Boolean.TRUE.equals(symbol.getIsEnabled())) throw new BusinessException("交易品种已停用");
        // 成交价只取服务端行情（含后台偏移）；限价单可在缺少行情时等待撮合。
        BigDecimal currentPrice = "MARKET".equals(req.getType())
                ? requireFreshPrice(req.getSymbol()) : quotes.freshPrice(req.getSymbol());
        // 获取合约设置
        BigDecimal lotSize = symbol.getLotSize() != null ? symbol.getLotSize() : BigDecimal.valueOf(1000);
        BigDecimal feeMultiplier = symbol.getFeeMultiplier() != null ? symbol.getFeeMultiplier() : BigDecimal.valueOf(30);
        BigDecimal maxLeverage = symbol.getMaxLeverage() != null ? symbol.getMaxLeverage() : BigDecimal.valueOf(100);
        if (!categories.leverageEnabled(symbol.getCategory())) maxLeverage = BigDecimal.ONE;
        TradeValidation.leverage(maxLeverage);
        BigDecimal leverage = req.getLeverage() != null ? req.getLeverage() : maxLeverage;
        TradeValidation.leverage(leverage);
        if (leverage.compareTo(maxLeverage) > 0) throw new BusinessException("杠杆倍数超过该品种上限: " + maxLeverage);
        TradeValidation.positive(lotSize, "每手数量");
        if (feeMultiplier.signum() < 0) throw new BusinessException("手续费设置无效");
        
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

        // 挂单按限价预留，成交时按实际成交价补足或退还差额。
        BigDecimal marginPrice = "LIMIT".equals(req.getType()) ? req.getPrice() : currentPrice;
        BigDecimal conversionRate=conversionRate(symbol.getQuoteCurrency(),symbol.getMarketSource());
        BigDecimal requiredMargin = calculateMargin(req.getQuantity(), lotSize, marginPrice, leverage, conversionRate);
        
        // 计算预计手续费 = 买入数量 × 手续费倍数
        BigDecimal fee = money(req.getQuantity().multiply(feeMultiplier), RoundingMode.HALF_UP);
        
        // 总费用 = 预计保证金 + 预计手续费
        BigDecimal totalCost = money(requiredMargin.add(fee), RoundingMode.UNNECESSARY);

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
        order.setCurrentPrice(currentPrice);
        order.setStopLoss(req.getStopLoss());
        order.setTakeProfit(req.getTakeProfit());
        order.setMargin(requiredMargin);
        order.setFee(fee); // 手续费
        order.setLeverage(leverage); // 杠杆倍数
        order.setLotSize(lotSize);
        order.setQuoteCurrency(symbol.getQuoteCurrency());
        order.setQuoteSource(symbol.getMarketSource());
        order.setMarginConversionRate(conversionRate);
        order.setProfit(BigDecimal.ZERO);
        
        // 如果是市价单，立即开仓；如果是限价单，状态为挂单
        if ("MARKET".equals(req.getType())) {
            order.setStatus("OPEN");
            order.setOpenTime(LocalDateTime.now());
            order.setOpenPrice(currentPrice);
        } else {
            order.setStatus("PENDING");
            order.setLimitMatchEnabled(true);
            // 挂单阶段保留限价展示，成交时再写入服务端新鲜行情价。
            order.setOpenPrice(req.getPrice());
        }

        return contractOrderRepository.save(order);
    }

    /** Each fill and its margin adjustment commit together; an unfunded sell limit stays pending. */
    public int matchPendingLimitOrders() {
        int filled = 0;
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        for (ContractOrder order : contractOrderRepository.findByStatusAndTypeAndLimitMatchEnabledTrue("PENDING", "LIMIT")) {
            try {
                if (order.getLotSize() != null) {
                    filled += transaction.execute(status -> matchPendingLimitOrder(order.getId()));
                } else {
                    BigDecimal marketPrice = quotes.freshPrice(order.getSymbol());
                    if (marketPrice != null && marketPrice.signum() > 0) {
                        filled += contractOrderRepository.openPendingLimitOrder(order.getId(), order.getRowVersion(), marketPrice, LocalDateTime.now());
                    }
                }
            } catch (OptimisticLockingFailureException | BusinessException ignored) {
                // A competing fill, cancellation or balance update won; retry remaining orders next tick.
            }
        }
        return filled;
    }

    private int matchPendingLimitOrder(Long id) {
        ContractOrder order = contractOrderRepository.findById(id).orElse(null);
        if (order == null || !"PENDING".equals(order.getStatus()) || !order.isLimitMatchEnabled()) return 0;
        TradingSymbol symbol = tradingSymbolRepository.findBySymbol(order.getSymbol()).orElse(null);
        if (symbol == null || (!categories.leverageEnabled(symbol.getCategory()) && order.getLeverage().compareTo(BigDecimal.ONE)>0)) return 0;
        BigDecimal price = quotes.freshPrice(order.getSymbol());
        if (price == null || price.signum() <= 0
                || ("BUY".equals(order.getSide()) && price.compareTo(order.getPrice()) > 0)
                || ("SELL".equals(order.getSide()) && price.compareTo(order.getPrice()) < 0)) return 0;
        BigDecimal conversionRate=conversionRate(order.getQuoteCurrency(),order.getQuoteSource());
        BigDecimal margin = calculateMargin(order.getQuantity(), order.getLotSize(), price, order.getLeverage(),conversionRate);
        BigDecimal difference = margin.subtract(order.getMargin());
        AssetAccount account = assetAccountRepository.findByUserIdAndCoin(order.getUserId(), "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));
        if (difference.signum() > 0 && account.getAvailable().compareTo(difference) < 0) return 0;
        account.setAvailable(account.getAvailable().subtract(difference));
        account.setFrozen(account.getFrozen().add(difference));
        assetAccountRepository.save(account);
        order.setMargin(margin);
        order.setMarginConversionRate(conversionRate);
        order.setStatus("OPEN");
        order.setOpenPrice(price);
        order.setCurrentPrice(price);
        order.setOpenTime(LocalDateTime.now());
        contractOrderRepository.saveAndFlush(order);
        return 1;
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

    /** 平仓始终使用服务端新鲜行情。 */
    @Transactional
    public ContractOrder closeOrder(Long userId, Long orderId, BigDecimal closePrice) {
        ContractOrder order = contractOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        if (!order.getUserId().equals(userId)) throw new BusinessException("无权操作此订单");
        return settleOrder(order);
    }

    @Transactional
    public ContractOrder adminCloseOrder(Long orderId, BigDecimal closePrice) {
        return settleOrder(contractOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在")));
    }

    private ContractOrder settleOrder(ContractOrder order) {
        if (!"OPEN".equals(order.getStatus())) throw new BusinessException("只能平仓持仓中的订单");
        BigDecimal closePrice = requireFreshPrice(order.getSymbol());
        BigDecimal settlementRate=conversionRate(order.getQuoteCurrency(),order.getQuoteSource());
        BigDecimal profit = money(calculateQuoteProfit(order,closePrice).multiply(settlementRate),RoundingMode.HALF_UP);
        AssetAccount account = assetAccountRepository.findByUserIdAndCoin(order.getUserId(), "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));
        BigDecimal totalFrozen = order.getMargin().add(order.getFee());
        BigDecimal frozen = account.getFrozen() != null ? account.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(totalFrozen) < 0) throw new BusinessException("冻结金额不足");
        account.setFrozen(frozen.subtract(totalFrozen));
        // 新单收取已预留的手续费；历史订单保留原来的退款规则。
        BigDecimal refund = order.getLotSize() == null ? totalFrozen : order.getMargin();
        BigDecimal available = account.getAvailable() != null ? account.getAvailable() : BigDecimal.ZERO;
        account.setAvailable(available.add(refund).add(profit));
        assetAccountRepository.save(account);
        order.setStatus("CLOSED");
        order.setClosePrice(closePrice);
        order.setSettlementConversionRate(settlementRate);
        order.setCurrentPrice(closePrice);
        order.setProfit(profit);
        order.setCloseTime(LocalDateTime.now());
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
        com.gtcfesk.exchange.common.TradeValidation.optionalPositive(stopLoss, "止损价格");
        com.gtcfesk.exchange.common.TradeValidation.optionalPositive(takeProfit, "止盈价格");
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
                
                // 检查止损
                if (order.getStopLoss() != null && order.getStopLoss().compareTo(BigDecimal.ZERO) > 0) {
                    if ("BUY".equals(order.getSide())) {
                        // 买入订单：当前价 <= 止损价，触发止损
                        if (currentPrice.compareTo(order.getStopLoss()) <= 0) {
                            shouldClose = true;
                        }
                    } else {
                        // 卖出订单：当前价 >= 止损价，触发止损
                        if (currentPrice.compareTo(order.getStopLoss()) >= 0) {
                            shouldClose = true;
                        }
                    }
                }
                
                // 检查止盈（如果还没触发止损）
                if (!shouldClose && order.getTakeProfit() != null && order.getTakeProfit().compareTo(BigDecimal.ZERO) > 0) {
                    if ("BUY".equals(order.getSide())) {
                        // 买入订单：当前价 >= 止盈价，触发止盈
                        if (currentPrice.compareTo(order.getTakeProfit()) >= 0) {
                            shouldClose = true;
                        }
                    } else {
                        // 卖出订单：当前价 <= 止盈价，触发止盈
                        if (currentPrice.compareTo(order.getTakeProfit()) <= 0) {
                            shouldClose = true;
                        }
                    }
                }
                
                // 如果触发止盈或止损，自动平仓
                if (shouldClose) {
                    adminCloseOrder(order.getId(), null);
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
                    if (order.getLotSize() == null && order.getFee() != null) {
                        totalFee = totalFee.add(order.getFee());
                    }
                    
                    // 计算实时盈亏
                    BigDecimal profit = calculateProfit(order, currentPrice);
                    totalProfit = totalProfit.add(profit);
                }
                
                // 新单手续费不计入可抵亏权益，历史单保留可退手续费。
                BigDecimal totalMarginAndFee = totalMargin.add(totalFee);
                
                // 检查强制平仓条件：总亏损 >= (账户余额 + 保证金总和)
                // 总亏损 = -totalProfit（如果totalProfit为负数）
                if (totalProfit.compareTo(BigDecimal.ZERO) < 0) {
                    BigDecimal totalLoss = totalProfit.negate(); // 转换为正数（亏损金额）
                    BigDecimal availablePlusMargin = available.add(totalMarginAndFee);
                    
                    // 如果总亏损 >= (余额 + 保证金)，强制平仓所有订单
                    if (totalLoss.compareTo(availablePlusMargin) >= 0) {
                        if (userOrders.stream().anyMatch(order -> quotes.freshPrice(order.getSymbol()) == null)) continue;
                        for (ContractOrder order : userOrders) conversionRate(order.getQuoteCurrency(),order.getQuoteSource());
                        // 强制平仓该用户的所有订单
                        boolean allClosed = true;
                        for (ContractOrder order : userOrders) {
                            try {
                                BigDecimal closePrice = symbolPriceMap.get(order.getSymbol());
                                if (closePrice != null && closePrice.compareTo(BigDecimal.ZERO) > 0) {
                                    adminCloseOrder(order.getId(), closePrice);
                                } else {
                                    allClosed = false;
                                }
                            } catch (Exception e) {
                                allClosed = false;
                            }
                        }
                        // 只在所有持仓结算成功后处理穿仓；挂单冻结资金保持不变。
                        if (allClosed && contractAccount.getAvailable().signum() < 0) {
                            contractAccount.setAvailable(BigDecimal.ZERO);
                            assetAccountRepository.save(contractAccount);
                        }
                    }
                }
            } catch (Exception e) {
                // 静默处理异常，避免日志输出
            }
        }
    }
    
    private BigDecimal calculateMargin(BigDecimal quantity, BigDecimal lotSize, BigDecimal price, BigDecimal leverage, BigDecimal rate) {
        return money(quantity.multiply(lotSize).multiply(price).multiply(rate).divide(leverage, 16, RoundingMode.CEILING), RoundingMode.UNNECESSARY);
    }

    private BigDecimal money(BigDecimal amount, RoundingMode rounding) {
        BigDecimal rounded = amount.setScale(16, rounding);
        if (rounded.precision() - rounded.scale() > 16) throw new BusinessException("交易金额超出支持范围");
        return rounded;
    }

    /** 获取允许成交的服务端行情，包含后台价格偏移。 */
    private BigDecimal requireFreshPrice(String symbol) {
        BigDecimal price = quotes.freshPrice(symbol);
        if (price == null || price.signum() <= 0) {
            throw new BusinessException("行情暂不可用或报价已过期，请稍后重试");
        }
        return price;
    }

    /** 计算订单的实时盈亏。 */
    private BigDecimal conversionRate(String currency,String source) {
        return com.gtcfesk.exchange.market.QuoteCurrencyConversion.fixed(currency)?BigDecimal.ONE:quotes.requireConversionRate(currency,source);
    }
    private BigDecimal calculateProfit(ContractOrder order, BigDecimal currentPrice) {
        return money(calculateQuoteProfit(order,currentPrice).multiply(conversionRate(order.getQuoteCurrency(),order.getQuoteSource())),RoundingMode.HALF_UP);
    }
    private BigDecimal calculateQuoteProfit(ContractOrder order, BigDecimal currentPrice) {
        if (order.getOpenPrice() == null || currentPrice == null 
                || order.getOpenPrice().compareTo(BigDecimal.ZERO) <= 0 
                || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        // 新单按实际持仓数量计盈亏；NULL 快照的历史单继续使用原杠杆乘数。
        BigDecimal multiplier = order.getLotSize() != null ? order.getLotSize()
                : (order.getLeverage() != null ? order.getLeverage() : BigDecimal.ONE);
        
        // 计算价格差
        BigDecimal priceDiff;
        if ("BUY".equals(order.getSide())) {
            // 买入：价格差 = 当前价 - 开仓价
            priceDiff = currentPrice.subtract(order.getOpenPrice());
        } else {
            // 卖出：价格差 = 开仓价 - 当前价
            priceDiff = order.getOpenPrice().subtract(currentPrice);
        }
        
        return priceDiff.multiply(order.getQuantity()).multiply(multiplier);
    }
}


