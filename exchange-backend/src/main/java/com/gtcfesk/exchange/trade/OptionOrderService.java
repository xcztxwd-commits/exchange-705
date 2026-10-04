package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.OrderRequest;
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
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.repository.UserAccountRepository users;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;
    @org.springframework.beans.factory.annotation.Autowired
    private com.gtcfesk.exchange.activity.TrialFunds trialFunds;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.activity.ActivityService activities;
    private static BigDecimal trial(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @org.springframework.beans.factory.annotation.Autowired
    private com.gtcfesk.exchange.control.OperationalIssueService operationalIssues;

    private final com.gtcfesk.exchange.user.KycIdentityService identityService;

    private final OptionOrderRepository optionOrderRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final com.gtcfesk.exchange.repository.TradingSymbolRepository tradingSymbolRepository;
    private final OptionDurationRepository optionDurationRepository;
    private final com.gtcfesk.exchange.market.ForexQuoteMarketService quotes;

    /**
     * 创建期货订单（期权/秒合约）
     * 期货交易使用期权资产（OPTION）
     */
    @Transactional
    public OptionOrder createOrder(Long userId, CreateOptionOrderRequest req) {
        tenantPolicy.requireNewBusiness("option");
        if (trialFunds != null) { trialFunds.lock(userId); trialFunds.requireTrade(userId); }
        else identityService.requireTradingApproved(userId);
        String requestKey=OrderRequest.optional(req==null?null:req.getRequestId());
        String requestHash=requestKey==null?null:OrderRequest.hash("option",req.getSymbol(),req.getDirection(),req.getAmount(),req.getDuration(),OrderRequest.source(req.getFundingSource(),"OPTION"));
        if(requestKey!=null) {
            users.lockById(userId).orElseThrow(()->new BusinessException("用户不存在"));
            OptionOrder previous=optionOrderRepository.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId,requestKey).orElse(null);
            if(previous!=null) { OrderRequest.same(previous.getRequestHash(),requestHash); return previous; }
        }

        if (req == null || req.getSymbol() == null || !("UP".equals(req.getDirection()) || "DOWN".equals(req.getDirection()))
                || req.getDuration() == null || req.getDuration() <= 0) throw new BusinessException("交易参数无效");
        com.gtcfesk.exchange.common.TradeValidation.positive(req.getAmount(), "金额");
        com.gtcfesk.exchange.entity.TradingSymbol instrument=tradingSymbolRepository.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), req.getSymbol()).orElse(null);
        if (instrument==null||!Boolean.TRUE.equals(instrument.getIsEnabled())) {
            throw new BusinessException("交易品种不存在或已停用");
        }
        quotes.requireMarketWindow(instrument,req.getDuration());
        OptionDuration duration = optionDurationRepository.findByTenantIdAndDuration(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), req.getDuration())
                .filter(d -> Boolean.TRUE.equals(d.getEnabled())).orElseThrow(() -> new BusinessException("交易周期不存在或已停用"));
        if ((duration.getMinAmount() != null && req.getAmount().compareTo(duration.getMinAmount()) < 0)
                || (duration.getMaxAmount() != null && req.getAmount().compareTo(duration.getMaxAmount()) > 0)) {
            throw new BusinessException("金额不在该周期允许范围内");
        }
        // 开仓使用服务端新鲜行情，保留后台价格偏移，忽略客户端 currentPrice。
        BigDecimal openPrice = quotes.freshPrice(req.getSymbol());
        if (openPrice == null || openPrice.signum() <= 0) {
            throw new BusinessException("行情暂不可用或报价已过期，请稍后重试");
        }
        // 获取期权资产账户
        AssetAccount optionAccount = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "OPTION")
                .orElseThrow(() -> new BusinessException("期权账户不存在"));

        String fundingSource=trialFunds==null?(req.getFundingSource()==null?"OPTION":req.getFundingSource()):trialFunds.source(req.getFundingSource(),"OPTION");
        if(trialFunds==null&&"TRIAL".equals(fundingSource))throw new BusinessException("体验金账户不可用");
        BigDecimal trialReserved = BigDecimal.ZERO;String trialAllocations=null;
        if (trialFunds != null) {com.gtcfesk.exchange.activity.TrialFunds.Reservation reservation=trialFunds.reserve(userId,optionAccount,req.getAmount(),fundingSource,"OPTION","OPTION_RESERVE");trialReserved=reservation.trial;trialAllocations=reservation.allocations;}
        else {
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

        }

        // 创建订单
        OptionOrder order = new OptionOrder();
        order.setRequestKey(requestKey);order.setRequestHash(requestHash);
        order.setUserId(userId);
        order.setTrialReserved(trialReserved);order.setFundingSource(fundingSource);order.setTrialAllocations(trialAllocations);
        order.setSymbol(req.getSymbol());
        order.setDirection(req.getDirection()); // UP or DOWN
        order.setAmount(req.getAmount());
        order.setOpenPrice(openPrice);
        order.setStatus("TRADING");
        order.setDuration(req.getDuration());
        order.setProfit(BigDecimal.ZERO);
        order.setOpenTime(LocalDateTime.now());

        OptionOrder saved=optionOrderRepository.save(order);if(activities!=null)activities.trigger(userId,"API_OPTION_ORDER","AUTH_TRADE");return saved;
    }

    /**
     * 获取用户的期货订单列表
     */
    public List<OptionOrder> getUserOrders(Long userId, String status) {
        if (status != null && !status.isEmpty()) {
            return optionOrderRepository.findByTenantIdAndUserIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, status);
        }
        return optionOrderRepository.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId);
    }

    /**
     * 获取期权资产余额
     */
    public BigDecimal getOptionBalance(Long userId) {
        AssetAccount optionAccount = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "OPTION")
                .orElse(null);
        if (optionAccount == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal real = optionAccount.getAvailable() != null ? optionAccount.getAvailable() : BigDecimal.ZERO;
        return real;
    }

    /**
     * 自动结算到期的期权订单
     * 由定时任务调用，每秒执行一次
     */
    public void settleExpiredOrders(java.util.Map<String, BigDecimal> symbolPriceMap) {
        Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        List<OptionOrder> tradingOrders = optionOrderRepository.findByTenantIdAndStatus(tenant, "TRADING");
        org.springframework.transaction.support.TransactionTemplate settlement =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        // A failed order must roll back independently, including under TenantJobRunner's outer transaction.
        settlement.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        LocalDateTime now = LocalDateTime.now();
        for (OptionOrder order : tradingOrders) {
            try {
                LocalDateTime expireTime = order.getOpenTime().plusSeconds(order.getDuration());
                if (now.isBefore(expireTime)) continue;
                if (quotes.isMarketClosed(order.getSymbol())) continue; // Preserve expiry/escrow; settle when executable quotes return.
                settlement.execute(status -> {
                    closeOrder(order.getUserId(), order.getId(), null);
                    return null;
                });
            } catch (RuntimeException failure) {
                if (operationalIssues != null) operationalIssues.failed(tenant, "option-settle", failure);
                org.slf4j.LoggerFactory.getLogger(getClass()).error(
                        "Option settlement failed: tenant={}, order={}, type={}",
                        tenant, order.getId(), failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * 平仓（到期自动平仓或手动平仓）
     */
    @Transactional
    public OptionOrder closeOrder(Long userId, Long orderId, BigDecimal closePrice) {
        // Serialize the account before loading an order; never settle a stale pre-lock managed instance.
        if (trialFunds != null) trialFunds.lock(userId);
        OptionOrder order = optionOrderRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此订单");
        }

        if ("CLOSED".equals(order.getStatus())) return order;
        if (!"TRADING".equals(order.getStatus())) {
            throw new BusinessException("订单状态不正确，无法平仓");
        }

        closePrice = quotes.freshPrice(order.getSymbol());
        if (closePrice == null || closePrice.signum() <= 0) throw new BusinessException("行情暂不可用或报价已过期，暂缓结算");
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
                OptionDuration duration = optionDurationRepository.findByTenantIdAndDuration(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), order.getDuration())
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
                OptionDuration duration = optionDurationRepository.findByTenantIdAndDuration(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), order.getDuration())
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
        AssetAccount optionAccount = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "OPTION")
                .orElseThrow(() -> new BusinessException("期权账户不存在"));

        if (trialFunds != null) trialFunds.settle(userId, optionAccount, amount, trial(order.getTrialReserved()),order.getTrialAllocations(),order.getFundingSource(), profit, "OPTION_SETTLE:"+order.getId());
        else {
        // 解冻金额
        BigDecimal frozen = optionAccount.getFrozen() != null ? optionAccount.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(amount) < 0) throw new BusinessException("资产冻结金额不足");
        optionAccount.setFrozen(frozen.subtract(amount));

        // 添加盈亏到可用余额
        BigDecimal available = optionAccount.getAvailable() != null ? optionAccount.getAvailable() : BigDecimal.ZERO;
        available = available.add(amount).add(profit); // 返还本金 + 盈亏
        optionAccount.setAvailable(available);

        assetAccountRepository.save(optionAccount);
        }
        return optionOrderRepository.save(order);
    }
}


