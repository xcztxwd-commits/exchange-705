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
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService audit;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.admin.SystemConfigService configs;
    @org.springframework.beans.factory.annotation.Autowired
    private com.gtcfesk.exchange.activity.TrialFunds trialFunds;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.activity.ActivityService activities;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Value("${app.market.s3-scheduling-enabled:false}") private boolean s3SchedulingEnabled;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.market.FundingQuoteAuthority quoteAuthority;
    /** New authority mode never falls back to an uncertified legacy quote/money boundary. */
    private void requireLegacyQuoteBoundary() {
        if(s3SchedulingEnabled) throw new BusinessException("S3_QUOTE_REJECTED: 此旧报价资金入口尚未完成权威接线，请保持拒绝");
    }

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
        requireLegacyQuoteBoundary();
        tenantPolicy.requireNewBusiness("option");
        if (trialFunds != null) { trialFunds.lock(userId); trialFunds.requireTrade(userId); }
        else {
            users.lockById(userId).orElseThrow(() -> new BusinessException("用户不存在"));
            assetAccountRepository.lockByUserId(userId);
            identityService.requireTradingApproved(userId);
        }
        return createLocked(userId,req,null);
    }

    private OptionOrder createLocked(Long userId,CreateOptionOrderRequest req,java.util.Map<String,Object> prepared) {
        String requestKey=OrderRequest.optional(req==null?null:req.getRequestId());
        String requestHash=optionRequestHash(req,requestKey);
        if(requestKey!=null) {
            users.lockById(userId).orElseThrow(()->new BusinessException("用户不存在"));
            OptionOrder previous=optionOrderRepository.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId,requestKey).orElse(null);
            if(previous!=null) {
                if (entityManager != null) entityManager.refresh(previous, javax.persistence.LockModeType.PESSIMISTIC_READ);
                OrderRequest.same(previous.getRequestHash(),requestHash); return previous;
            }
        }

        if (req == null || req.getSymbol() == null || !("UP".equals(req.getDirection()) || "DOWN".equals(req.getDirection()))
                || req.getDuration() == null || req.getDuration() <= 0) throw new BusinessException("交易参数无效");
        com.gtcfesk.exchange.common.TradeValidation.positive(req.getAmount(), "金额");
        com.gtcfesk.exchange.entity.TradingSymbol instrument=prepared==null
                ?tradingSymbolRepository.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),req.getSymbol()).orElse(null)
                :currentPreparedInstrument(req.getSymbol(),prepared);
        if (instrument==null||!Boolean.TRUE.equals(instrument.getIsEnabled())) {
            throw new BusinessException("交易品种不存在或已停用");
        }
        if(prepared==null)quotes.requireMarketWindow(instrument,req.getDuration());else requireCurrentMarket(instrument,req.getDuration());
        OptionDuration duration=prepared==null
                ?optionDurationRepository.findByTenantIdAndDuration(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),req.getDuration()).orElse(null)
                :lockCurrentDuration(req.getDuration());
        if(duration==null||!Boolean.TRUE.equals(duration.getEnabled()))throw new BusinessException("交易周期不存在或已停用");
        if ((duration.getMinAmount() != null && req.getAmount().compareTo(duration.getMinAmount()) < 0)
                || (duration.getMaxAmount() != null && req.getAmount().compareTo(duration.getMaxAmount()) > 0)) {
            throw new BusinessException("金额不在该周期允许范围内");
        }
        // 开仓使用服务端新鲜行情，保留后台价格偏移，忽略客户端 currentPrice。
        BigDecimal openPrice=prepared==null?quotes.freshPrice(req.getSymbol()):new BigDecimal(prepared.get("price").toString());
        if (openPrice == null || openPrice.signum() <= 0) {
            throw new BusinessException("行情暂不可用或报价已过期，请稍后重试");
        }
        // 获取期权资产账户
        AssetAccount optionAccount = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "OPTION")
                .orElseThrow(() -> new BusinessException("期权账户不存在"));

        String fundingSource=trialFunds==null?(req.getFundingSource()==null?"OPTION":req.getFundingSource()):trialFunds.source(req.getFundingSource(),"OPTION");
        if(trialFunds==null&&"TRIAL".equals(fundingSource))throw new BusinessException("体验金账户不可用");
        if(prepared!=null){
            tenantPolicy.requireCurrentNewBusiness("option");
            identityService.requireCurrentTradingApproved(userId);
            // All quote/config/KYC authority is held before legacy grant materialization or expiry maintenance.
            trialFunds.lock(userId);
        }
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

        order.setPromotionPending(activities != null);
        OptionOrder saved = optionOrderRepository.save(order);
        moneyAudit("OPTION_CREATE", saved);
        return saved;
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
        if(s3SchedulingEnabled) requireS3Boundary();
        Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        org.springframework.transaction.support.TransactionTemplate settlement =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        // Retain independent commits for explicitly transactional legacy callers. The scheduler
        // uses eachContext, so its normal path has no suspended outer connection or money locks.
        settlement.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        LocalDateTime now = LocalDateTime.now();
        long after = 0;
        while (true) {
            List<OptionOrderRepository.ExpiryCandidate> page = optionOrderRepository.expiryCandidates(
                    tenant, after, org.springframework.data.domain.PageRequest.of(0, 100));
            if (page.isEmpty()) break;
            for (OptionOrderRepository.ExpiryCandidate candidate : page) {
                after = candidate.getId();
                try {
                    if (candidate.getOpenTime() == null || candidate.getDuration() == null
                            || candidate.getDuration() <= 0) throw new BusinessException("期权到期配置异常");
                    if (now.isBefore(candidate.getOpenTime().plusSeconds(candidate.getDuration()))) continue;
                    OptionOrder route = optionOrderRepository.findByTenantIdAndId(tenant, candidate.getId()).orElse(null);
                    if(route == null) continue;
                    if(s3SchedulingEnabled) {
                        closeOrderS3(candidate.getUserId(),candidate.getId(),true);
                        continue;
                    }
                    if(quotes.isMarketClosed(route.getSymbol())) continue;
                    settlement.execute(status -> {
                        closeOrder(candidate.getUserId(), candidate.getId(), null);
                        return null;
                    });
                } catch (RuntimeException failure) {
                    if (operationalIssues != null) operationalIssues.failed(tenant, "option-settle", failure);
                    org.slf4j.LoggerFactory.getLogger(getClass()).error(
                            "Option settlement failed: tenant={}, order={}, type={}",
                            tenant, candidate.getId(), failure.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * 平仓（到期自动平仓或手动平仓）
     */
    @Transactional
    public OptionOrder closeOrder(Long userId, Long orderId, BigDecimal closePrice) {
        requireLegacyQuoteBoundary();
        // Serialize the account before loading an order; never settle a stale pre-lock managed instance.
        if (trialFunds != null) trialFunds.lock(userId);
        else {
            users.lockById(userId).orElseThrow(() -> new BusinessException("用户不存在"));
            assetAccountRepository.lockByUserId(userId);
        }
        OptionOrder order=lockCurrentOrder(userId,orderId);
        if ("CLOSED".equals(order.getStatus())) return order;
        if (!"TRADING".equals(order.getStatus())) throw new BusinessException("订单状态不正确，无法平仓");

        closePrice = quotes.freshPrice(order.getSymbol());
        if (closePrice == null || closePrice.signum() <= 0) throw new BusinessException("行情暂不可用或报价已过期，暂缓结算");
        OptionDuration duration=order.getDuration()==null?null:optionDurationRepository.findByTenantIdAndDuration(
                com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),order.getDuration()).orElse(null);
        return settleLockedOrder(userId,order,closePrice,duration);
    }

    private OptionOrder lockCurrentOrder(Long userId,Long orderId) {
        // Refresh any stale managed @Version row before Hibernate attempts a lock upgrade.
        if (entityManager != null) {
            entityManager.flush();
            java.util.List<?> current = entityManager.createNativeQuery(
                    "SELECT id FROM option_order WHERE tenant_id=?1 AND id=?2 FOR UPDATE")
                    .setParameter(1, com.gtcfesk.exchange.tenant.TenantContext.requireTenantId())
                    .setParameter(2, orderId).getResultList();
            for (Object value : current) {
                OptionOrder managed = entityManager.find(OptionOrder.class, ((Number) value).longValue());
                entityManager.refresh(managed, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
                com.gtcfesk.exchange.tenant.TenantContext.require(managed.getTenantId());
            }
        }
        OptionOrder order = optionOrderRepository.lockById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        if (entityManager != null) entityManager.refresh(order, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此订单");
        }

        return order;
    }

    private OptionOrder settleLockedOrder(Long userId,OptionOrder order,BigDecimal closePrice,OptionDuration currentDuration) {
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
                OptionDuration duration = currentDuration;
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
                OptionDuration duration = currentDuration;
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
        OptionOrder saved = optionOrderRepository.save(order);
        moneyAudit("OPTION_SETTLE", saved);
        return saved;
    }

    /** Request receipt is recovered before any new quote, including an unknown COMMIT acknowledgment. */
    public OptionOrder createOrderWithAuthority(Long userId,CreateOptionOrderRequest req) {
        if(!s3SchedulingEnabled)return required().execute(status -> createOrder(userId,req));
        requireS3Boundary();
        String key=OrderRequest.optional(req==null?null:req.getRequestId()),hash=optionRequestHash(req,key);
        OptionOrder receipt=required().execute(status -> currentCreateReceipt(userId,key,hash));
        if(receipt!=null)return receipt;
        if(req==null||req.getSymbol()==null)throw new BusinessException("交易参数无效");
        java.util.Map<String,Object> prepared;
        try{prepared=quoteAuthority.prepare(req.getSymbol());}
        catch(BusinessException unavailable){
            OptionOrder committed=required().execute(status -> currentCreateReceipt(userId,key,hash));
            if(committed!=null)return committed;throw unavailable;
        }
        final java.util.Map<String,Object> candidate=prepared;
        return required().execute(status -> {
            trialFunds.lockForQuoteAndPendingExpiry(userId);
            OptionOrder committed=createReceipt(userId,key,hash);if(committed!=null)return committed;
            quoteAuthority.validate(java.util.Collections.singletonList(candidate));
            return createLocked(userId,req,candidate);
        });
    }
    private String optionRequestHash(CreateOptionOrderRequest req,String key){
        return key==null?null:OrderRequest.hash("option",req.getSymbol(),req.getDirection(),req.getAmount(),req.getDuration(),OrderRequest.source(req.getFundingSource(),"OPTION"));
    }
    private OptionOrder currentCreateReceipt(Long user,String key,String hash){
        if(key==null)return null;
        trialFunds.lockForQuoteAndPendingExpiry(user);return createReceipt(user,key,hash);
    }
    private OptionOrder createReceipt(Long user,String key,String hash){
        if(key==null)return null;
        OptionOrder previous=optionOrderRepository.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),user,key).orElse(null);
        if(previous!=null){entityManager.refresh(previous,javax.persistence.LockModeType.PESSIMISTIC_WRITE);OrderRequest.same(previous.getRequestHash(),hash);}return previous;
    }
    private com.gtcfesk.exchange.entity.TradingSymbol currentPreparedInstrument(String code,java.util.Map<String,Object> candidate){
        if(!java.util.Objects.equals(code,candidate.get("configuredCode")))throw new BusinessException("S3_QUOTE_REJECTED: 期权当前报价归属已变化");
        com.gtcfesk.exchange.entity.TradingSymbol instrument=entityManager.find(com.gtcfesk.exchange.entity.TradingSymbol.class,
                com.gtcfesk.exchange.market.QuoteState.time(candidate.get("symbolId")));
        if(instrument==null)throw new BusinessException("S3_QUOTE_REJECTED: 期权当前品种不存在");
        entityManager.refresh(instrument,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        com.gtcfesk.exchange.tenant.TenantContext.require(instrument.getTenantId());
        if(!java.util.Objects.equals(code,instrument.getSymbol()))throw new BusinessException("S3_QUOTE_REJECTED: 期权当前品种已变化");return instrument;
    }

    /** Public HTTP facade: legacy close still joins REQUIRED; enabled preparation never borrows funding locks. */
    public OptionOrder closeOrderWithAuthority(Long userId,Long orderId,BigDecimal ignoredClientPrice) {
        if(!s3SchedulingEnabled) return required().execute(status -> closeOrder(userId,orderId,ignoredClientPrice));
        return closeOrderS3(userId,orderId,false);
    }

    private org.springframework.transaction.support.TransactionTemplate required() {
        org.springframework.transaction.support.TransactionTemplate transaction=
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        // Only new S3 units use RC. After the user WRITE lock, the optional trial-anchor
        // lookup must see a grant committed while waiting, without a missing-row gap lock
        // or authorization-before-DML upsert. The legacy REQUIRED branch keeps DEFAULT.
        if(s3SchedulingEnabled)transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private void requireS3Boundary() {
        if(!s3SchedulingEnabled || quoteAuthority==null || trialFunds==null || transactionManager==null || entityManager==null || audit==null)
            throw new BusinessException("S3_QUOTE_REJECTED: 期权资金权威不可用");
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new BusinessException("S3_QUOTE_REJECTED: 期权准备不得加入外层资金事务");
    }

    private OptionOrder currentCandidate(Long userId,Long orderId) {
        trialFunds.lockForQuote(userId);
        OptionOrder order=lockCurrentOrder(userId,orderId);
        if(!"CLOSED".equals(order.getStatus()) && !"TRADING".equals(order.getStatus()))
            throw new BusinessException("订单状态不正确，无法平仓");
        if(!"CLOSED".equals(order.getStatus()) && order.getFundingSource()!=null
                && !"OPTION".equals(order.getFundingSource()) && !"TRIAL".equals(order.getFundingSource()))
            throw new BusinessException("订单资金来源不一致");
        return order;
    }

    private boolean expiredCurrent(OptionOrder order) {
        if(order.getOpenTime()==null || order.getDuration()==null || order.getDuration()<=0)
            throw new BusinessException("期权到期配置异常");
        return !LocalDateTime.now().isBefore(order.getOpenTime().plusSeconds(order.getDuration()));
    }

    private OptionOrder closeOrderS3(Long userId,Long orderId,boolean expiredOnly) {
        requireS3Boundary();
        // A separate short current-read transaction recovers an unknown-COMMIT result without any new quote.
        OptionOrder route=required().execute(status -> currentCandidate(userId,orderId));
        if("CLOSED".equals(route.getStatus()) || expiredOnly&&!expiredCurrent(route)) return route;
        String preparedSymbol=route.getSymbol();
        java.util.Map<String,Object> prepared;
        try { prepared=quoteAuthority.prepare(preparedSymbol); }
        catch(BusinessException unavailable) {
            // Another closer can finish between the first current receipt read and outside-TX preparation.
            OptionOrder receipt=required().execute(status -> currentCandidate(userId,orderId));
            if("CLOSED".equals(receipt.getStatus())) return receipt;
            throw unavailable;
        }
        final java.util.Map<String,Object> candidate=prepared;
        return required().execute(status -> closePrepared(userId,orderId,candidate,expiredOnly,null));
    }

    /** Trusted whole-command composition; preparation belongs outside the caller's funding transaction. */
    public OptionOrder closePrepared(Long userId,Long orderId,java.util.Map<String,Object> candidate,Runnable beforeSettlement) {
        return closePrepared(userId,orderId,candidate,false,beforeSettlement);
    }
    private OptionOrder closePrepared(Long userId,Long orderId,java.util.Map<String,Object> candidate,boolean expiredOnly,Runnable beforeSettlement) {
        if(!s3SchedulingEnabled||quoteAuthority==null||candidate==null||!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new BusinessException("S3_QUOTE_REJECTED: 持有调用者资金事务及锁外报价才能组合受控退出");
            OptionOrder order=currentCandidate(userId,orderId);
            if("CLOSED".equals(order.getStatus()) || expiredOnly&&!expiredCurrent(order)) return order;
            if(!java.util.Objects.equals(order.getSymbol(),candidate.get("configuredCode")))
                throw new BusinessException("S3_QUOTE_REJECTED: 期权当前报价归属已变化");
            // No reserve/settle/maintenance/audit DML has occurred in this physical transaction.
            quoteAuthority.validate(java.util.Collections.singletonList(candidate));
            long symbolId=com.gtcfesk.exchange.market.QuoteState.time(candidate.get("symbolId"));
            com.gtcfesk.exchange.entity.TradingSymbol instrument=entityManager.find(
                    com.gtcfesk.exchange.entity.TradingSymbol.class,symbolId);
            if(instrument==null) throw new BusinessException("S3_QUOTE_REJECTED: 期权当前品种不存在");
            entityManager.refresh(instrument,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
            com.gtcfesk.exchange.tenant.TenantContext.require(instrument.getTenantId());
            if(!java.util.Objects.equals(order.getSymbol(),instrument.getSymbol()))
                throw new BusinessException("S3_QUOTE_REJECTED: 期权当前品种已变化");
            // Existing tenant anchor protects the absence of a historical duration in RC.
            // Admin duration writers take tenant before duration, so readers use the same order.
            tenantPolicy.lockCurrentTenant();
            OptionDuration duration=lockCurrentDuration(order.getDuration());
            requireCurrentMarket(instrument);
            BigDecimal price=new BigDecimal(candidate.get("price").toString());
            if(price.signum()<=0)throw new BusinessException("行情暂不可用或报价已过期，暂缓结算");
            // Existing close does not apply new-business/KYC gates. Do not strand accepted positions.
            if(beforeSettlement!=null)beforeSettlement.run();
            return settleLockedOrder(userId,order,price,duration);
    }

    private OptionDuration lockCurrentDuration(Integer seconds) {
        if(seconds==null) return null; // Preserve the legacy default-rate rule for historical rows.
        org.hibernate.query.NativeQuery<?> query=entityManager.createNativeQuery(
                "SELECT * FROM option_duration WHERE tenant_id=?1 AND duration=?2 ORDER BY id FOR UPDATE")
                .unwrap(org.hibernate.query.NativeQuery.class);
        query.addEntity("locked",OptionDuration.class,org.hibernate.LockMode.NONE);
        query.setParameter(1,com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());query.setParameter(2,seconds);
        java.util.List<?> rows=query.getResultList();
        if(rows.size()>1)throw new BusinessException("期权期限配置不唯一");
        if(rows.isEmpty())return null;
        OptionDuration current=(OptionDuration)rows.get(0);
        entityManager.refresh(current,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        com.gtcfesk.exchange.tenant.TenantContext.require(current.getTenantId());
        return current;
    }

    private void requireCurrentMarket(com.gtcfesk.exchange.entity.TradingSymbol instrument) {requireCurrentMarket(instrument,0);}
    private void requireCurrentMarket(com.gtcfesk.exchange.entity.TradingSymbol instrument,int seconds) {
        String raw=configs.getCurrentConfigValue(com.gtcfesk.exchange.market.MarketHoursConfig.KEY);
        final com.gtcfesk.exchange.market.MarketHoursConfig.Settings settings=
                com.gtcfesk.exchange.market.MarketHoursConfig.parse(raw);
        assertMarketOpen(settings,instrument,seconds);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public void beforeCommit(boolean readOnly){assertMarketOpen(settings,instrument,seconds);}
                });
    }

    private void assertMarketOpen(com.gtcfesk.exchange.market.MarketHoursConfig.Settings settings,
                                  com.gtcfesk.exchange.entity.TradingSymbol instrument,int seconds) {
        com.gtcfesk.exchange.market.MarketHoursConfig.Status state=com.gtcfesk.exchange.market.MarketHoursConfig.evaluate(
                settings,instrument,java.time.Instant.now());
        if(state.closed)throw new BusinessException("当前休市（"+state.reason+"），暂缓结算");
        if(seconds>0&&state.nextChangeAt!=null&&java.time.Instant.now().plusSeconds(seconds).toEpochMilli()>=state.nextChangeAt)
            throw new BusinessException("该交易周期跨越计划休市，请缩短周期或等待开市");
    }

    private void moneyAudit(String action, OptionOrder order) {
        // Spring requires the audit bean. The null branch supports existing direct-construction
        // arithmetic fixtures, never an optional production dependency.
        if (audit == null) return;
        String detail = "userId=" + order.getUserId() + "; amount=" + order.getAmount()
                + "; status=" + order.getStatus() + "; closePrice=" + order.getClosePrice()
                + "; profit=" + order.getProfit() + "; fundingSource=" + order.getFundingSource();
        if (com.gtcfesk.exchange.control.ControlIdentity.current() != null)
            audit.recordCurrent(action, order.getId().toString(), detail, null);
        else audit.record(null, com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), null,
                action, order.getId().toString(), "SUCCESS", detail, null);
    }
}


