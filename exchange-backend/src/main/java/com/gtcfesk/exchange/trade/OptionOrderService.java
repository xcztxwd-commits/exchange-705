package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.OptionDuration;
import com.gtcfesk.exchange.entity.OptionOrder;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.OptionDurationRepository;
import com.gtcfesk.exchange.repository.OptionOrderRepository;
import com.gtcfesk.exchange.trade.dto.CreateOptionOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OptionOrderService {

    private final OptionOrderRepository optionOrderRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final OptionDurationRepository optionDurationRepository;
    private final com.gtcfesk.exchange.market.ForexQuoteMarketService quotes;

    /**
     * 创建期货订单（期权/秒合约）
     * 期货交易使用期权资产（OPTION）
     */
    @Transactional
    public OptionOrder createOrder(Long userId, CreateOptionOrderRequest req) {
        // 获取期权资产账户
        AssetAccount optionAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "OPTION")
                .orElseThrow(() -> new BusinessException("期权账户不存在"));

        // 检查余额是否足够
        BigDecimal available = optionAccount.getAvailable() != null ? optionAccount.getAvailable() : BigDecimal.ZERO;
        if (available.compareTo(req.getAmount()) < 0) {
            throw new BusinessException("期权资产余额不足");
        }

        // 冻结金额
        optionAccount.setAvailable(available.subtract(req.getAmount()));
        BigDecimal frozen = optionAccount.getFrozen() != null ? optionAccount.getFrozen() : BigDecimal.ZERO;
        optionAccount.setFrozen(frozen.add(req.getAmount()));
        assetAccountRepository.save(optionAccount);

        // 创建订单
        OptionOrder order = new OptionOrder();
        order.setUserId(userId);
        order.setSymbol(req.getSymbol());
        order.setDirection(req.getDirection()); // UP or DOWN
        order.setAmount(req.getAmount());
        order.setOpenPrice(req.getCurrentPrice());
        order.setStatus("TRADING");
        order.setDuration(req.getDuration());
        order.setProfit(BigDecimal.ZERO);
        order.setOpenTime(LocalDateTime.now());

        return optionOrderRepository.save(order);
    }

    /**
     * 获取用户的期货订单列表
     */
    public List<OptionOrder> getUserOrders(Long userId, String status) {
        if (status != null && !status.isEmpty()) {
            return optionOrderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
        }
        return optionOrderRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 获取期权资产余额
     */
    public BigDecimal getOptionBalance(Long userId) {
        AssetAccount optionAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "OPTION")
                .orElse(null);
        if (optionAccount == null) {
            return BigDecimal.ZERO;
        }
        return optionAccount.getAvailable() != null ? optionAccount.getAvailable() : BigDecimal.ZERO;
    }

    /**
     * 自动结算到期的期权订单
     * 由定时任务调用，每秒执行一次
     */
    @Transactional
    public void settleExpiredOrders(java.util.Map<String, BigDecimal> symbolPriceMap) {
        // 获取所有处于交易中的期权订单
        List<OptionOrder> tradingOrders = optionOrderRepository.findByStatus("TRADING");
        if (tradingOrders.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (OptionOrder order : tradingOrders) {
            // 计算到期时间
            LocalDateTime expireTime = order.getOpenTime().plusSeconds(order.getDuration());
            
            // 如果已经到期
            if (now.isAfter(expireTime) || now.isEqual(expireTime)) {
                // 获取当前价格，如果获取不到则跳过本次结算
                BigDecimal currentPrice = quotes.freshPrice(order.getSymbol());

                
                if (currentPrice != null && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    try {
                        closeOrder(order.getUserId(), order.getId(), currentPrice);
                    } catch (Exception e) {
                        // 记录异常，但不中断其他订单的结算
                        System.err.println("结算期权订单失败: " + order.getId() + ", " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 平仓（到期自动平仓或手动平仓）
     */
    @Transactional
    public OptionOrder closeOrder(Long userId, Long orderId, BigDecimal closePrice) {
        OptionOrder order = optionOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此订单");
        }

        if (!"TRADING".equals(order.getStatus())) {
            throw new BusinessException("订单状态不正确，无法平仓");
        }

        closePrice = quotes.freshPrice(order.getSymbol());
        if (closePrice == null) throw new BusinessException("行情暂不可用或报价已过期，暂缓结算");
        // 计算盈亏
        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal openPrice = order.getOpenPrice();
        BigDecimal amount = order.getAmount();

        // 如果订单有预设盈亏类型，使用预设的盈亏状态
        if (order.getPresetProfitType() != null && !order.getPresetProfitType().isEmpty()) {
            // 获取期限设置中的盈亏比例和亏损比例
            BigDecimal profitRate = new BigDecimal("0.8"); // 默认80%
            BigDecimal lossRate = new BigDecimal("1.0"); // 默认100%（全部亏损）
            if (order.getDuration() != null) {
                OptionDuration duration = optionDurationRepository.findByDuration(order.getDuration())
                    .orElse(null);
                if (duration != null) {
                    if (duration.getProfitRate() != null) {
                        profitRate = duration.getProfitRate();
                    }
                    if (duration.getLossRate() != null) {
                        lossRate = duration.getLossRate();
                    }
                }
            }
            
            // 根据预设盈亏类型计算盈亏
            if ("PROFIT".equals(order.getPresetProfitType())) {
                // 预设为盈利：使用盈亏比例计算盈利
                profit = amount.multiply(profitRate);
            } else if ("LOSS".equals(order.getPresetProfitType())) {
                // 预设为亏损：使用亏损比例计算亏损
                profit = amount.multiply(lossRate).negate();
            }
        } else if (openPrice != null && closePrice != null && amount != null) {
            // 没有预设盈亏类型，使用实际价格计算
            BigDecimal priceDiff = closePrice.subtract(openPrice);
            
            // 获取期限设置中的盈亏比例和亏损比例
            BigDecimal profitRate = new BigDecimal("0.8"); // 默认80%
            BigDecimal lossRate = new BigDecimal("1.0"); // 默认100%（全部亏损）
            if (order.getDuration() != null) {
                OptionDuration duration = optionDurationRepository.findByDuration(order.getDuration())
                    .orElse(null);
                if (duration != null) {
                    if (duration.getProfitRate() != null) {
                        profitRate = duration.getProfitRate();
                    }
                    if (duration.getLossRate() != null) {
                        lossRate = duration.getLossRate();
                    }
                }
            }
            
            // 买涨：价格上涨盈利，价格下跌亏损
            // 买跌：价格下跌盈利，价格上涨亏损
            if ("UP".equals(order.getDirection())) {
                // 买涨：使用期限设置中的盈亏比例
                if (priceDiff.compareTo(BigDecimal.ZERO) > 0) {
                    profit = amount.multiply(profitRate);
                } else {
                    // 使用亏损比例计算亏损
                    profit = amount.multiply(lossRate).negate();
                }
            } else if ("DOWN".equals(order.getDirection())) {
                // 买跌：使用期限设置中的盈亏比例
                if (priceDiff.compareTo(BigDecimal.ZERO) < 0) {
                    profit = amount.multiply(profitRate);
                } else {
                    // 使用亏损比例计算亏损
                    profit = amount.multiply(lossRate).negate();
                }
            }
        }

        // 更新订单状态
        order.setStatus("CLOSED");
        order.setClosePrice(closePrice);
        order.setProfit(profit);
        order.setCloseTime(LocalDateTime.now());

        // 更新资产账户
        AssetAccount optionAccount = assetAccountRepository
                .findByUserIdAndCoin(userId, "OPTION")
                .orElseThrow(() -> new BusinessException("期权账户不存在"));

        // 解冻金额
        BigDecimal frozen = optionAccount.getFrozen() != null ? optionAccount.getFrozen() : BigDecimal.ZERO;
        frozen = frozen.subtract(amount);
        optionAccount.setFrozen(frozen.max(BigDecimal.ZERO));

        // 添加盈亏到可用余额
        BigDecimal available = optionAccount.getAvailable() != null ? optionAccount.getAvailable() : BigDecimal.ZERO;
        available = available.add(amount).add(profit); // 返还本金 + 盈亏
        optionAccount.setAvailable(available);

        assetAccountRepository.save(optionAccount);
        return optionOrderRepository.save(order);
    }
}


