package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.OrderRequest;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContractOrderService {
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.repository.UserAccountRepository users;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.admin.SystemConfigService configs;
    @org.springframework.beans.factory.annotation.Autowired
    private com.gtcfesk.exchange.activity.TrialFunds trialFunds;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.activity.ActivityService activities;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService audit;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.market.FundingQuoteAuthority quoteAuthority;
    @org.springframework.beans.factory.annotation.Value("${app.market.s3-scheduling-enabled:false}") private boolean s3SchedulingEnabled;
    /** New authority mode never falls back to an uncertified legacy quote/money boundary. */
    private void requireLegacyQuoteBoundary() {
        if(s3SchedulingEnabled) throw new BusinessException("S3_QUOTE_REJECTED: 此旧报价资金入口尚未完成权威接线，请保持拒绝");
    }

    private static BigDecimal trial(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private final com.gtcfesk.exchange.user.KycIdentityService identityService;

    private final ContractOrderRepository contractOrderRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final TradingSymbolRepository tradingSymbolRepository;
    private final com.gtcfesk.exchange.market.ForexQuoteMarketService quotes;
    private final PlatformTransactionManager transactionManager;
    private final com.gtcfesk.exchange.market.MarketCategoryService categories;

    @javax.persistence.PersistenceContext
    private javax.persistence.EntityManager entityManager;

    /**
     * 创建合约订单
     * 合约交易使用合约资产（CONTRACT）
     */
    @Transactional
    public ContractOrder createOrder(Long userId, CreateContractOrderRequest req) {
        requireLegacyQuoteBoundary();
        tenantPolicy.requireNewBusiness("contract");
        lockFunding(userId);
        if (trialFunds != null) trialFunds.requireTrade(userId);
        else identityService.requireTradingApproved(userId);
        return createLocked(userId,req,null);
    }
    private ContractOrder createLocked(Long userId,CreateContractOrderRequest req,Map<String,Object> prepared) {
        String requestKey=OrderRequest.optional(req==null?null:req.getRequestId());
        String requestHash=contractRequestHash(req,requestKey);
        if(requestKey!=null) {
            users.lockById(userId).orElseThrow(()->new BusinessException("用户不存在"));
            ContractOrder previous=contractOrderRepository.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId,requestKey).orElse(null);
            if(previous!=null) {
                if (entityManager != null) entityManager.refresh(previous, javax.persistence.LockModeType.PESSIMISTIC_READ);
                OrderRequest.same(previous.getRequestHash(),requestHash); return previous;
            }
        }

        if (req == null || req.getSymbol() == null || req.getSymbol().trim().isEmpty()
                || !("BUY".equals(req.getSide()) || "SELL".equals(req.getSide()))
                || !("MARKET".equals(req.getType()) || "LIMIT".equals(req.getType()))) {
            throw new BusinessException("交易品种、方向或订单类型无效");
        }
        TradeValidation.positive(req.getQuantity(), "数量");
        if ("LIMIT".equals(req.getType())) com.gtcfesk.exchange.common.TradeValidation.positive(req.getPrice(), "限价");
        com.gtcfesk.exchange.common.TradeValidation.optionalPositive(req.getStopLoss(), "止损价格");
        com.gtcfesk.exchange.common.TradeValidation.optionalPositive(req.getTakeProfit(), "止盈价格");
        // 获取交易对信息
        TradingSymbol symbol=prepared==null
                ?tradingSymbolRepository.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),req.getSymbol()).orElseThrow(()->new BusinessException("交易对不存在"))
                :currentPreparedInstrument(req.getSymbol(),prepared);
        
        if (entityManager != null) entityManager.refresh(symbol, javax.persistence.LockModeType.PESSIMISTIC_READ);
        QuantityRules.protocol(symbol, req.getSpecVersion(), req.getQuantityUnitType());
        QuantityRules.quantity(symbol, req.getQuantity());
        if (!Boolean.TRUE.equals(symbol.getIsEnabled())) throw new BusinessException("交易品种已停用");
        if(prepared==null)quotes.requireMarketOpen(symbol);else requireCurrentMarket(symbol); // Includes new limit orders; cancellation remains allowed.
        boolean forex = FxContractRules.isForex(symbol);
        FxContractRules.validate(symbol);
        // 成交价只取服务端行情（含后台偏移）；限价单可在缺少行情时等待撮合。
        BigDecimal currentPrice=prepared!=null?new BigDecimal(prepared.get("price").toString())
                :"MARKET".equals(req.getType())?requireFreshPrice(req.getSymbol()):quotes.freshPrice(req.getSymbol());
        // 获取合约设置
        BigDecimal lotSize = symbol.getLotSize() != null ? symbol.getLotSize() : BigDecimal.valueOf(1000);
        BigDecimal feeMultiplier = symbol.getFeeMultiplier() != null ? symbol.getFeeMultiplier() : BigDecimal.valueOf(30);
        BigDecimal maxLeverage = symbol.getMaxLeverage() != null ? symbol.getMaxLeverage() : BigDecimal.valueOf(100);
        if (!(prepared==null?categories.leverageEnabled(symbol.getCategory()):categories.currentLeverageEnabled(symbol.getCategory()))) maxLeverage = BigDecimal.ONE;
        TradeValidation.leverage(maxLeverage);
        BigDecimal leverage = req.getLeverage() != null ? req.getLeverage() : maxLeverage;
        TradeValidation.leverage(leverage);
        if (leverage.compareTo(maxLeverage) > 0) throw new BusinessException("杠杆倍数超过该品种上限: " + maxLeverage);
        TradeValidation.positive(lotSize, "每手数量");
        if (feeMultiplier.signum() < 0) throw new BusinessException("手续费设置无效");
        
        if(prepared!=null){tenantPolicy.requireCurrentNewBusiness("contract");identityService.requireCurrentTradingApproved(userId);}
        // 获取或创建合约资产账户
        AssetAccount contractAccount = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "CONTRACT")
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
        BigDecimal settlementRate=prepared==null?conversionRate(symbol.getQuoteCurrency(),symbol.getMarketSource())
                :decimal(prepared,"quoteToUsdRate","conversionAvailable","conversionExpiresAt");
        BigDecimal conversionRate=forex ? prepared==null?quotes.fxMarginRate(symbol.getBaseCurrency(),symbol.getQuoteCurrency(),marginPrice)
                :"USD".equals(symbol.getBaseCurrency())?BigDecimal.ONE:"USD".equals(symbol.getQuoteCurrency())?marginPrice
                :decimal(prepared,"marginBaseToUsdRate","marginRateAvailable","marginRateExpiresAt") :settlementRate;
        QuantityRules.notional(req.getQuantity(),lotSize,marginPrice,settlementRate,symbol.getMinOrderNotional());
        BigDecimal requiredMargin = calculateMargin(req.getQuantity(), lotSize, forex ? BigDecimal.ONE : marginPrice, leverage, conversionRate);
        
        // 计算预计手续费 = 买入数量 × 手续费倍数
        BigDecimal fee = money(req.getQuantity().multiply(feeMultiplier), RoundingMode.HALF_UP);
        
        // 总费用 = 预计保证金 + 预计手续费
        BigDecimal totalCost = money(requiredMargin.add(fee), RoundingMode.UNNECESSARY);

        String fundingSource = trialFunds == null ? (req.getFundingSource() == null ? "CONTRACT" : req.getFundingSource()) : trialFunds.source(req.getFundingSource(),"CONTRACT");
        if(trialFunds==null&&"TRIAL".equals(fundingSource))throw new BusinessException("体验金账户不可用");
        if(prepared!=null)trialFunds.lock(userId); // Current authority is complete before legacy grant/expiry maintenance.
        BigDecimal trialReserved = BigDecimal.ZERO;String trialAllocations=null;
        if (trialFunds != null) {com.gtcfesk.exchange.activity.TrialFunds.Reservation reservation=trialFunds.reserve(userId, contractAccount, totalCost, fundingSource,"CONTRACT","CONTRACT_RESERVE");trialReserved=reservation.trial;trialAllocations=reservation.allocations;}
        else {
            BigDecimal available = contractAccount.getAvailable();
            if (available.compareTo(totalCost) < 0) throw new BusinessException("合约资产余额不足");
            contractAccount.setAvailable(available.subtract(totalCost));
            contractAccount.setFrozen(contractAccount.getFrozen().add(totalCost));
            assetAccountRepository.save(contractAccount);
        }

        // 创建订单
        ContractOrder order = new ContractOrder();
        order.setRequestKey(requestKey);order.setRequestHash(requestHash);
        order.setUserId(userId);
        order.setTrialReserved(trialReserved);order.setFundingSource(fundingSource);order.setTrialAllocations(trialAllocations);
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
        QuantityRules.snapshot(symbol, order);
        order.setFxBaseCurrency(forex ? symbol.getBaseCurrency() : null);
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

        order.setPromotionPending(activities != null);
        ContractOrder saved = contractOrderRepository.save(order);
        auditOrder("CONTRACT_CREATE", saved, settlementEvidence(saved));
        return saved;
    }

    /** Each fill and its margin adjustment commit together; an unfunded sell limit stays pending. */
    public int matchPendingLimitOrders() {
        requireLegacyQuoteBoundary();
        int filled = 0;
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        List<ContractOrder> pending = new ArrayList<>(contractOrderRepository.findByTenantIdAndStatusAndTypeAndLimitMatchEnabledTrue(
                com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), "PENDING", "LIMIT"));
        pending.sort(Comparator.comparing(ContractOrder::getId));
        // A legacy TenantJobRunner caller deliberately keeps whole-tenant atomic semantics.
        if (TransactionSynchronizationManager.isActualTransactionActive()) lockBatchFunding(pending);
        for (ContractOrder order : pending) {
            try {
                if (!identityService.canUseTradingFunds(order.getUserId())) continue;
                if (order.getLotSize() != null) {
                    filled += transaction.execute(status -> matchPendingLimitOrder(order.getId()));
                } else {
                    BigDecimal marketPrice = quotes.freshPrice(order.getSymbol());
                    if (marketPrice != null && marketPrice.signum() > 0) {
                        List<String> expectedRoute = quoteRoute(order);
                        Long expectedOwner = order.getUserId();
                        long expectedVersion = order.getRowVersion();
                        filled += transaction.execute(status -> {
                            lockFunding(expectedOwner);
                            ContractOrder current = contractOrderRepository.lockById(order.getId()).orElse(null);
                            if (current == null) return 0;
                            if (entityManager != null) entityManager.refresh(current, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
                            if (!Objects.equals(expectedOwner, current.getUserId()) || !expectedRoute.equals(quoteRoute(current)))
                                throw new BusinessException("订单归属或报价依据已变化，请重试");
                            if (current.getRowVersion() != expectedVersion || !identityService.canUseTradingFunds(current.getUserId())) return 0;
                            int changed = contractOrderRepository.openPendingLimitOrder(current.getId(), current.getRowVersion(), marketPrice, LocalDateTime.now());
                            if (changed != 0) {
                                if (entityManager != null) entityManager.refresh(current, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
                                Map<String, Object> evidence = settlementEvidence(current); evidence.put("price", marketPrice);
                                auditOrder("CONTRACT_FILL", current, evidence);
                            }
                            return changed;
                        });
                    }
                }
            } catch (OptimisticLockingFailureException | BusinessException ignored) {
                // A competing fill, cancellation or balance update won; retry remaining orders next tick.
            }
        }
        return filled;
    }

    private int matchPendingLimitOrder(Long id) {
        ContractOrder order = contractOrderRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).orElse(null);
        if (order == null || !"PENDING".equals(order.getStatus()) || !order.isLimitMatchEnabled()) return 0;
        if (!identityService.canUseTradingFunds(order.getUserId())) return 0;
        Long owner = order.getUserId();
        lockFunding(owner);
        if(entityManager!=null)entityManager.refresh(order,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(!Objects.equals(owner,order.getUserId()))throw new BusinessException("订单归属已变化，请重试");
        if(!"PENDING".equals(order.getStatus()))return 0;
        if(trialFunds!=null&&"TRIAL".equals(order.getFundingSource())&&trialFunds.reservationExpired(order.getTrialAllocations()))return 0;
        TradingSymbol symbol = tradingSymbolRepository.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), order.getSymbol()).orElse(null);
        if (symbol == null || (!categories.leverageEnabled(symbol.getCategory()) && order.getLeverage().compareTo(BigDecimal.ONE)>0)) return 0;
        BigDecimal price = quotes.freshPrice(order.getSymbol());
        if (price == null || price.signum() <= 0
                || ("BUY".equals(order.getSide()) && price.compareTo(order.getPrice()) > 0)
                || ("SELL".equals(order.getSide()) && price.compareTo(order.getPrice()) < 0)) return 0;
        boolean forex = order.getFxBaseCurrency() != null;
        BigDecimal conversionRate=forex ? quotes.fxMarginRate(order.getFxBaseCurrency(),order.getQuoteCurrency(),price)
                : conversionRate(order.getQuoteCurrency(),order.getQuoteSource());
        QuantityRules.notional(order.getQuantity(), order.getLotSize(), price, conversionRate(order.getQuoteCurrency(), order.getQuoteSource()), order.getMinOrderNotional());
        BigDecimal margin = calculateMargin(order.getQuantity(), order.getLotSize(), forex ? BigDecimal.ONE : price, order.getLeverage(),conversionRate);
        BigDecimal difference = margin.subtract(order.getMargin());
        AssetAccount account = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), order.getUserId(), "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));
        if (trialFunds != null) {
            BigDecimal balance = order.getFundingSource()==null ? trialFunds.tradingBalance(order.getUserId(),account.getAvailable()) : trialFunds.selectedBalance(order.getUserId(),account.getAvailable(),order.getFundingSource());
            if (difference.signum() > 0 && balance.compareTo(difference) < 0) return 0;
            BigDecimal oldCost = order.getMargin().add(order.getFee());
            com.gtcfesk.exchange.activity.TrialFunds.Reservation resized=trialFunds.resize(order.getUserId(),account,oldCost,margin.add(order.getFee()),trial(order.getTrialReserved()),order.getTrialAllocations(),order.getFundingSource(),"CONTRACT","CONTRACT_RESIZE:"+id);
            order.setTrialReserved(resized.trial);order.setTrialAllocations(resized.allocations);
        } else {
            if (difference.signum() > 0 && account.getAvailable().compareTo(difference) < 0) return 0;
            account.setAvailable(account.getAvailable().subtract(difference));
            account.setFrozen(account.getFrozen().add(difference));
            assetAccountRepository.save(account);
        }
        order.setMargin(margin);
        order.setMarginConversionRate(conversionRate);
        order.setStatus("OPEN");
        order.setOpenPrice(price);
        order.setCurrentPrice(price);
        order.setOpenTime(LocalDateTime.now());
        contractOrderRepository.saveAndFlush(order);
        auditOrder("CONTRACT_FILL", order, settlementEvidence(order));
        return 1;
    }

    /**
     * 获取用户的合约订单列表
     */
    public List<ContractOrder> getUserOrders(Long userId, String status) {
        if (status != null && !status.isEmpty()) {
            return contractOrderRepository.findByTenantIdAndUserIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, status);
        }
        return contractOrderRepository.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId);
    }

    /**
     * 获取合约资产余额
     */
    public BigDecimal getContractBalance(Long userId) {
        AssetAccount contractAccount = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "CONTRACT")
                .orElseGet(() -> {
                    // 如果账户不存在，自动创建
                    AssetAccount newAccount = new AssetAccount();
                    newAccount.setUserId(userId);
                    newAccount.setCoin("CONTRACT");
                    newAccount.setAvailable(BigDecimal.ZERO);
                    newAccount.setFrozen(BigDecimal.ZERO);
                    return assetAccountRepository.save(newAccount);
                });
        BigDecimal real = contractAccount.getAvailable() != null ? contractAccount.getAvailable() : BigDecimal.ZERO;
        return real;
    }

    private TransactionTemplate publicFundingTransaction(){
        TransactionTemplate transaction=new TransactionTemplate(transactionManager);
        if(s3SchedulingEnabled)transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }
    public ContractOrder createOrderWithAuthority(Long user,CreateContractOrderRequest req){
        if(!s3SchedulingEnabled)return publicFundingTransaction().execute(status->createOrder(user,req));
        requireS3SchedulingReady();
        String key=OrderRequest.optional(req==null?null:req.getRequestId()),hash=contractRequestHash(req,key);
        ContractOrder receipt=publicFundingTransaction().execute(status->currentCreateReceipt(user,key,hash));if(receipt!=null)return receipt;
        if(req==null||req.getSymbol()==null)throw new BusinessException("交易参数无效");
        PreparedQuotes prepared;ContractOrder route=new ContractOrder();
        try{
            TradingSymbol symbol=tradingSymbolRepository.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),req.getSymbol()).orElseThrow(()->new BusinessException("交易对不存在"));
            route.setSymbol(req.getSymbol());route.setQuoteCurrency(symbol.getQuoteCurrency());route.setQuoteSource(symbol.getMarketSource());
            route.setFxBaseCurrency(FxContractRules.isForex(symbol)?symbol.getBaseCurrency():null);route.setFundingSource(trialFunds.source(req.getFundingSource(),"CONTRACT"));
            // Enabled LIMIT is deliberately quote-authorized too: no uncertified configuration-only reservation.
            prepared=prepareQuotesS3(Collections.singletonList(route));
        }catch(BusinessException unavailable){
            ContractOrder committed=publicFundingTransaction().execute(status->currentCreateReceipt(user,key,hash));if(committed!=null)return committed;throw unavailable;
        }
        final PreparedQuotes candidate=prepared;
        return publicFundingTransaction().execute(status->{
            trialFunds.lockForQuoteAndPendingExpiry(user);ContractOrder committed=createReceipt(user,key,hash);if(committed!=null)return committed;
            quoteAuthority.validate(candidate.entries.values());return createLocked(user,req,candidate.forOrder(route));
        });
    }
    private String contractRequestHash(CreateContractOrderRequest req,String key){
        return key==null?null:OrderRequest.hash("contract",req.getSymbol(),req.getSide(),req.getType(),req.getQuantity(),req.getLeverage(),"LIMIT".equals(req.getType())?req.getPrice():null,req.getStopLoss(),req.getTakeProfit(),req.getSpecVersion(),req.getQuantityUnitType(),OrderRequest.source(req.getFundingSource(),"CONTRACT"));
    }
    private ContractOrder currentCreateReceipt(Long user,String key,String hash){
        if(key==null)return null;trialFunds.lockForQuoteAndPendingExpiry(user);return createReceipt(user,key,hash);
    }
    private ContractOrder createReceipt(Long user,String key,String hash){
        if(key==null)return null;ContractOrder row=contractOrderRepository.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),user,key).orElse(null);
        if(row!=null){entityManager.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_WRITE);OrderRequest.same(row.getRequestHash(),hash);}return row;
    }
    /** Controller facade; disabled legacy calls still join their caller's REQUIRED transaction. */
    public ContractOrder closeOrderWithAuthority(Long user,Long id,BigDecimal ignoredClientPrice){
        if(!s3SchedulingEnabled)return publicFundingTransaction().execute(status->closeOrder(user,id,ignoredClientPrice));
        requireS3SchedulingReady();
        ContractOrder receipt=publicFundingTransaction().execute(status->currentCloseReceipt(user,id));if("CLOSED".equals(receipt.getStatus()))return receipt;
        Map<String,Object> prepared;
        try{prepared=prepareCloseQuote(id);}catch(BusinessException unavailable){
            ContractOrder committed=publicFundingTransaction().execute(status->currentCloseReceipt(user,id));if("CLOSED".equals(committed.getStatus()))return committed;throw unavailable;
        }
        final Map<String,Object> candidate=prepared;
        return publicFundingTransaction().execute(status->{
            ContractOrder current=currentCloseReceipt(user,id);if("CLOSED".equals(current.getStatus()))return current;
            return closePrepared(user,id,candidate,()->{});
        });
    }
    private ContractOrder currentCloseReceipt(Long user,Long id){trialFunds.lockForQuoteAndPendingExpiry(user);return currentContractOrder(user,id);}
    private ContractOrder currentContractOrder(Long user,Long id){
        org.hibernate.query.NativeQuery<?> query=entityManager.createNativeQuery("SELECT * FROM contract_order WHERE tenant_id=?1 AND id=?2 FOR UPDATE").unwrap(org.hibernate.query.NativeQuery.class);
        query.addEntity("locked",ContractOrder.class,org.hibernate.LockMode.NONE);query.setParameter(1,com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());query.setParameter(2,id);
        List<?> rows=query.getResultList();if(rows.size()!=1)throw new BusinessException("订单不存在");ContractOrder order=(ContractOrder)rows.get(0);entityManager.refresh(order,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(!Objects.equals(user,order.getUserId()))throw new BusinessException("无权操作此订单");return order;
    }
    /** Controlled exits call this before opening their complete receipt/snapshot/funding transaction. */
    public Map<String,Object> prepareCloseQuote(Long id){
        requireS3SchedulingReady();ContractOrder route=contractOrderRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),id).orElseThrow(()->new BusinessException("订单不存在"));
        Map<String,Object> quote=new LinkedHashMap<>(prepareQuotesS3(Collections.singletonList(route)).forOrder(route));
        quote.put("contractOrderId",id);quote.put("contractRoute",Collections.unmodifiableList(new ArrayList<>(quoteRoute(route))));return Collections.unmodifiableMap(quote);
    }
    /** One caller-owned physical transaction. The callback runs only after all current authority and before settlement. */
    public ContractOrder closePrepared(Long user,Long id,Map<String,Object> prepared,Runnable beforeSettlement){
        if(!s3SchedulingEnabled||!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Prepared contract close requires the caller funding transaction");
        trialFunds.lockForQuoteAndPendingExpiry(user);ContractOrder order=currentContractOrder(user,id);
        if(!"OPEN".equals(order.getStatus()))throw new BusinessException("只能平仓持仓中的订单");
        if(com.gtcfesk.exchange.market.QuoteState.time(prepared.get("contractOrderId"))!=id||!quoteRoute(order).equals(prepared.get("contractRoute")))throw new BusinessException("订单归属或报价依据已变化，请重试");
        requireS3SupportedOrder(order);quoteAuthority.validate(Collections.singletonList(prepared));
        requireCurrentMarket(currentPreparedInstrument(order.getSymbol(),prepared));
        BigDecimal price=decimal(prepared,"price","available","executionExpiresAt"),rate=decimal(prepared,"quoteToUsdRate","conversionAvailable","conversionExpiresAt");
        beforeSettlement.run();trialFunds.lock(user);
        AssetAccount account=assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),user,"CONTRACT").orElseThrow(()->new BusinessException("合约资产账户不存在"));
        ContractOrder settled=settleLockedOrder(order,account,price,rate);auditOrder("CONTRACT_CLOSE",settled,prepared);return settled;
    }
    private TradingSymbol currentPreparedInstrument(String code,Map<String,Object> prepared){
        if(!Objects.equals(code,prepared.get("configuredCode")))throw new BusinessException("S3报价品种已变化");
        TradingSymbol symbol=entityManager.find(TradingSymbol.class,com.gtcfesk.exchange.market.QuoteState.time(prepared.get("symbolId")));
        if(symbol==null)throw new BusinessException("交易对不存在");entityManager.refresh(symbol,javax.persistence.LockModeType.PESSIMISTIC_WRITE);com.gtcfesk.exchange.tenant.TenantContext.require(symbol.getTenantId());
        if(!Objects.equals(code,symbol.getSymbol()))throw new BusinessException("S3报价品种已变化");return symbol;
    }
    private void requireCurrentMarket(TradingSymbol symbol){
        final com.gtcfesk.exchange.market.MarketHoursConfig.Settings settings=com.gtcfesk.exchange.market.MarketHoursConfig.parse(configs.getCurrentConfigValue(com.gtcfesk.exchange.market.MarketHoursConfig.KEY));
        Runnable check=()->{com.gtcfesk.exchange.market.MarketHoursConfig.Status state=com.gtcfesk.exchange.market.MarketHoursConfig.evaluate(settings,symbol,java.time.Instant.now());if(state.closed)throw new BusinessException("当前休市（"+state.reason+"）");};
        check.run();TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){check.run();}});
    }

    /** 平仓始终使用服务端新鲜行情。 */
    @Transactional
    public ContractOrder closeOrder(Long userId, Long orderId, BigDecimal closePrice) {
        requireLegacyQuoteBoundary();
        ContractOrder order = contractOrderRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        if (!order.getUserId().equals(userId)) throw new BusinessException("无权操作此订单");
        return settleOrder(order);
    }

    @Transactional
    public ContractOrder adminCloseOrder(Long orderId, BigDecimal closePrice) {
        requireLegacyQuoteBoundary();
        return settleOrder(contractOrderRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), orderId)
                .orElseThrow(() -> new BusinessException("订单不存在")));
    }

    private ContractOrder settleOrder(ContractOrder order) {
        if (!"OPEN".equals(order.getStatus())) throw new BusinessException("只能平仓持仓中的订单");
        BigDecimal closePrice = requireFreshPrice(order.getSymbol());
        BigDecimal settlementRate=conversionRate(order.getQuoteCurrency(),order.getQuoteSource());
        Long owner = order.getUserId();
        List<String> quoteRoute = quoteRoute(order);
        lockFunding(owner);
        // Reload after quote/account locks: a concurrent price refresh may have advanced the version.
        if (entityManager != null) entityManager.refresh(order, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(owner, order.getUserId()) || !quoteRoute.equals(quoteRoute(order)))
            throw new BusinessException("订单归属或报价依据已变化，请重试");
        if (!"OPEN".equals(order.getStatus())) throw new BusinessException("只能平仓持仓中的订单");
        AssetAccount account = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), order.getUserId(), "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));
        ContractOrder settled = settleLockedOrder(order, account, closePrice, settlementRate);
        auditOrder("CONTRACT_CLOSE", settled, settlementEvidence(settled));
        return settled;
    }

    /** Caller already owns funding and order locks; no quote/RPC or independent commit here. */
    private ContractOrder settleLockedOrder(ContractOrder order, AssetAccount account, BigDecimal closePrice, BigDecimal settlementRate) {
        BigDecimal profit = money(calculateQuoteProfit(order,closePrice).multiply(settlementRate),RoundingMode.HALF_UP);
        BigDecimal totalFrozen = order.getMargin().add(order.getFee());
        if (trialFunds != null) {
            BigDecimal net = order.getLotSize() == null ? profit : profit.subtract(order.getFee());
            trialFunds.settle(order.getUserId(), account, totalFrozen, trial(order.getTrialReserved()), order.getTrialAllocations(),order.getFundingSource(),net, "CONTRACT_SETTLE:"+order.getId());
        } else {
        BigDecimal frozen = account.getFrozen() != null ? account.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(totalFrozen) < 0) throw new BusinessException("冻结金额不足");
        account.setFrozen(frozen.subtract(totalFrozen));
        // 新单收取已预留的手续费；历史订单保留原来的退款规则。
        BigDecimal refund = order.getLotSize() == null ? totalFrozen : order.getMargin();
        BigDecimal available = account.getAvailable() != null ? account.getAvailable() : BigDecimal.ZERO;
        account.setAvailable(available.add(refund).add(profit));
        assetAccountRepository.save(account);
        }
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
        ContractOrder order = contractOrderRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        // 检查订单是否属于该用户
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此订单");
        }

        // 检查订单状态
        if (!"PENDING".equals(order.getStatus())) throw new BusinessException("只能取消挂单中的订单");
        // Expiry may cancel the pending order while the user lock is acquired below.

        // 获取合约资产账户
        lockFunding(userId);
        if(entityManager!=null)entityManager.refresh(order,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(!Objects.equals(userId,order.getUserId()))throw new BusinessException("无权操作此订单");
        if("CANCELLED".equals(order.getStatus())&&expiredPendingCancellation(order))return order;
        if(!"PENDING".equals(order.getStatus()))throw new BusinessException("只能取消挂单中的订单");
        order.setStatus("CANCELLED");
        AssetAccount contractAccount = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));

        // 解冻保证金和手续费
        BigDecimal totalFrozen = order.getMargin().add(order.getFee());
        if (trialFunds != null) trialFunds.settle(userId, contractAccount, totalFrozen, trial(order.getTrialReserved()),order.getTrialAllocations(),order.getFundingSource(), BigDecimal.ZERO, "CONTRACT_CANCEL:"+order.getId());
        else {
        BigDecimal frozen = contractAccount.getFrozen() != null ? contractAccount.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(totalFrozen) < 0) {
            throw new BusinessException("冻结金额不足");
        }
        contractAccount.setFrozen(frozen.subtract(totalFrozen));

        // 返还保证金和手续费
        BigDecimal available = contractAccount.getAvailable() != null ? contractAccount.getAvailable() : BigDecimal.ZERO;
        contractAccount.setAvailable(available.add(totalFrozen));
        assetAccountRepository.save(contractAccount);

        }

        ContractOrder saved = contractOrderRepository.save(order);
        auditOrder("CONTRACT_CANCEL", saved, settlementEvidence(saved));
        return saved;
    }

    /**
     * 管理员撤单
     */
    @Transactional
    public ContractOrder adminCancelOrder(Long orderId) {
        ContractOrder order = contractOrderRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        // 检查订单状态
        if (!"PENDING".equals(order.getStatus())) {
            throw new BusinessException("只能取消挂单中的订单");
        }

        Long userId = order.getUserId();

        // Acquire funding locks before changing status; expiry must still find this pending order.

        // 获取合约资产账户
        lockFunding(userId);
        if(entityManager!=null)entityManager.refresh(order,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(!Objects.equals(userId,order.getUserId()))throw new BusinessException("无权操作此订单");
        if("CANCELLED".equals(order.getStatus())&&expiredPendingCancellation(order))return order;
        if(!"PENDING".equals(order.getStatus()))throw new BusinessException("只能取消挂单中的订单");
        order.setStatus("CANCELLED");
        AssetAccount contractAccount = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));

        // 解冻保证金和手续费
        BigDecimal totalFrozen = order.getMargin().add(order.getFee());
        if (trialFunds != null) trialFunds.settle(userId, contractAccount, totalFrozen, trial(order.getTrialReserved()),order.getTrialAllocations(),order.getFundingSource(), BigDecimal.ZERO, "CONTRACT_CANCEL:"+order.getId());
        else {
        BigDecimal frozen = contractAccount.getFrozen() != null ? contractAccount.getFrozen() : BigDecimal.ZERO;
        if (frozen.compareTo(totalFrozen) < 0) {
            throw new BusinessException("冻结金额不足");
        }
        contractAccount.setFrozen(frozen.subtract(totalFrozen));

        // 返还保证金和手续费
        BigDecimal available = contractAccount.getAvailable() != null ? contractAccount.getAvailable() : BigDecimal.ZERO;
        contractAccount.setAvailable(available.add(totalFrozen));
        assetAccountRepository.save(contractAccount);

        }

        ContractOrder saved = contractOrderRepository.save(order);
        auditOrder("CONTRACT_CANCEL", saved, settlementEvidence(saved));
        return saved;
    }

    /** Only the funding-lock expiry transition may acknowledge an already-cancelled pending order. */
    private boolean expiredPendingCancellation(ContractOrder order) {
        if(trialFunds==null||entityManager==null||!"TRIAL".equals(order.getFundingSource())
                ||!trialFunds.reservationExpired(order.getTrialAllocations()))return false;
        return entityManager.createQuery("select count(l) from TrialLedger l where l.tenantId=:tenant and l.userId=:user and l.reason=:reason",Long.class)
                .setParameter("tenant",com.gtcfesk.exchange.tenant.TenantContext.requireTenantId())
                .setParameter("user",order.getUserId()).setParameter("reason","TRIAL_EXPIRED_CANCEL:"+order.getId()).getSingleResult()==1L;
    }

    /**
     * 更新订单的止盈止损
     */
    @Transactional
    public ContractOrder updateStopLossTakeProfit(Long userId, Long orderId, BigDecimal stopLoss, BigDecimal takeProfit) {
        com.gtcfesk.exchange.common.TradeValidation.optionalPositive(stopLoss, "止损价格");
        com.gtcfesk.exchange.common.TradeValidation.optionalPositive(takeProfit, "止盈价格");
        ContractOrder order = contractOrderRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        // 检查订单是否属于该用户
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此订单");
        }

        lockFunding(userId);
        if (entityManager != null) entityManager.refresh(order, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(userId, order.getUserId())) throw new BusinessException("无权操作此订单");
        // Check terminal state after the same funding/order lock chain used by close.
        if (!"OPEN".equals(order.getStatus())) {
            throw new BusinessException("只能修改持仓中订单的止盈止损");
        }

        // 更新止盈止损
        order.setStopLoss(stopLoss);
        order.setTakeProfit(takeProfit);

        ContractOrder saved = contractOrderRepository.save(order);
        auditOrder("CONTRACT_RISK_UPDATE", saved, settlementEvidence(saved));
        return saved;
    }
    
    /**
     * 检查并自动平仓触发止盈止损的订单
     * @param symbol 交易对符号，如果为null则检查所有交易对
     * @param currentPrice 当前价格
     */
    @Transactional
    public void checkAndAutoCloseSnapshotOrders() {
        requireLegacyQuoteBoundary();
        // The outer snapshot API may visit symbols in any order; acquire every owner first.
        lockBatchFunding(contractOrderRepository.findByTenantIdAndStatus(
                com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), "OPEN"));
        for (String symbol : quotes.freshPrices().keySet()) checkAndAutoCloseOrders(symbol, quotes.freshPrice(symbol));
    }

    @Transactional
    public void checkAndAutoCloseOrders(String symbol, BigDecimal currentPrice) {
        requireLegacyQuoteBoundary();
        currentPrice = quotes.freshPrice(symbol);
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        
        // 获取需要检查的订单列表
        List<ContractOrder> ordersToCheck;
        if (symbol != null && !symbol.isEmpty()) {
            ordersToCheck = contractOrderRepository.findByTenantIdAndSymbolAndStatus(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), symbol, "OPEN");
        } else {
            ordersToCheck = contractOrderRepository.findByTenantIdAndStatus(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), "OPEN");
        }
        
        ordersToCheck = new ArrayList<>(ordersToCheck);
        ordersToCheck.sort(Comparator.comparing(ContractOrder::getId));
        lockBatchFunding(ordersToCheck);
        for (ContractOrder order : ordersToCheck) {
            if (!"OPEN".equals(order.getStatus())) continue;
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
            } catch (RuntimeException failure) {
                // The tenant batch is atomic: never commit a partial cash/trial settlement.
                throw failure;
            }
        }
    }
    
    /**
     * 检查并强制平仓：当合约所有订单的总盈亏亏损大于（合约账户余额+保证金）时，自动强制平仓
     * @param symbolPriceMap 交易对符号到当前价格的映射，如果为null则只检查已更新currentPrice的订单
     */
    @Transactional
    public void checkAndForceCloseOrders(Map<String, BigDecimal> symbolPriceMap) {
        requireLegacyQuoteBoundary();
        symbolPriceMap = quotes.freshPrices();
        // 获取所有持仓订单
        List<ContractOrder> openOrders = contractOrderRepository.findByTenantIdAndStatus(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), "OPEN");
        if (openOrders.isEmpty()) {
            return;
        }
        openOrders = new ArrayList<>(openOrders);
        openOrders.sort(Comparator.comparing(ContractOrder::getId));
        lockBatchFunding(openOrders);
        openOrders.removeIf(order -> !"OPEN".equals(order.getStatus()));
        // Stable user/source visitation after canonical funding and order locks.
        // 按用户分组
        Map<String,List<ContractOrder>> ordersByUser=openOrders.stream().collect(Collectors.groupingBy(o->o.getUserId()+":"+(o.getFundingSource()==null?"LEGACY":o.getFundingSource()), TreeMap::new, Collectors.toList()));
        
        // 对每个用户检查强制平仓条件
        for (Map.Entry<String, List<ContractOrder>> entry : ordersByUser.entrySet()) {
            Long userId = entry.getValue().get(0).getUserId();
            String fundingSource = entry.getValue().get(0).getFundingSource();
            List<ContractOrder> userOrders = contractOrderRepository.lockOpenPortfolio(
                    com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, fundingSource);
            for (ContractOrder order : userOrders) {
                if (entityManager != null) entityManager.refresh(order, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
                if (!Objects.equals(userId, order.getUserId()) || !Objects.equals(fundingSource, order.getFundingSource()) || !"OPEN".equals(order.getStatus()))
                    throw new BusinessException("组合仓位已变化，请重试");
            }
            if (userOrders.isEmpty()) continue;
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
                AssetAccount contractAccount = assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "CONTRACT")
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
                    String source=userOrders.get(0).getFundingSource();
                    BigDecimal selected=trialFunds==null?available:source==null?trialFunds.tradingBalance(userId,available):trialFunds.selectedBalance(userId,available,source);
                    BigDecimal availablePlusMargin=selected.add(totalMarginAndFee);
                    
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
                            } catch (RuntimeException failure) {
                                throw failure;
                            }
                        }
                        // 只在所有持仓结算成功后处理穿仓；挂单冻结资金保持不变。
                        if (allClosed && contractAccount.getAvailable().signum() < 0) {
                            contractAccount.setAvailable(BigDecimal.ZERO);
                            assetAccountRepository.save(contractAccount);
                        }
                    }
                }
            } catch (RuntimeException failure) {
                // The tenant batch is atomic: never commit a partial cash/trial settlement.
                throw failure;
            }
        }
    }
    
    /** Production lock anchor; nullable users only preserves directly constructed historical cash fixtures. */
    private void lockFunding(Long userId) {
        if (trialFunds != null) trialFunds.lock(userId);
        else {
            if (users != null) users.lockById(userId).orElseThrow(() -> new BusinessException("用户不存在"));
            assetAccountRepository.lockByUserId(userId);
        }
    }

    /** Legacy full-tenant calls keep one transaction, but never acquire owners in symbol/hash order. */
    private void lockBatchFunding(List<ContractOrder> orders) {
        List<Long> owners = orders.stream().map(ContractOrder::getUserId).distinct().sorted().collect(Collectors.toList());
        Map<Long, Long> expectedOwners = orders.stream().collect(Collectors.toMap(ContractOrder::getId, ContractOrder::getUserId));
        if (users != null) for (Long owner : owners)
            users.lockById(owner).orElseThrow(() -> new BusinessException("用户不存在"));
        for (Long owner : owners) assetAccountRepository.lockByUserId(owner);
        // TrialFunds reuses these anchors and performs synchronous expiry/grant checks.
        if (trialFunds != null) for (Long owner : owners) trialFunds.lock(owner);
        if (entityManager != null) {
            List<ContractOrder> ordered = new ArrayList<>(orders);
            ordered.sort(Comparator.comparing(ContractOrder::getId));
            for (ContractOrder order : ordered) {
                entityManager.refresh(order, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
                if (!Objects.equals(expectedOwners.get(order.getId()), order.getUserId()))
                    throw new BusinessException("订单归属已变化，请重试");
            }
        }
    }

    private static List<String> quoteRoute(ContractOrder order) {
        return Arrays.asList(order.getSymbol(), order.getQuoteCurrency(), order.getQuoteSource(), order.getFxBaseCurrency());
    }

    /**
     * S3 scheduling contract: ID scans and quote preparation do not own a money transaction.
     * The default caller remains disabled. An explicit non-production caller uses database
     * authority in each funding transaction; unsupported legacy/TRIAL conversions fail closed.
     */
    private void requireS3SchedulingReady() {
        requireNoS3OuterTransaction();
        com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        if(!s3SchedulingEnabled || quoteAuthority==null)
            throw new BusinessException("S3合约单元调度未启用：待S2事务内报价权威合同确认");
    }

    private void requireNoS3OuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("S3合约调度不允许外层资金事务");
    }

    /** Explicit new partial-success contract; the old matchPendingLimitOrders contract is unchanged. */
    public Map<Long, String> matchPendingLimitOrdersS3(int pageSize) {
        requireS3SchedulingReady();
        return scheduleOrdinaryS3(true, pageSize);
    }

    /** Explicit per-order stop/take-profit contract; the old whole-tenant API remains atomic. */
    public Map<Long, String> checkAndAutoCloseOrdersS3(int pageSize) {
        requireS3SchedulingReady();
        return scheduleOrdinaryS3(false, pageSize);
    }

    private Map<Long, String> scheduleOrdinaryS3(boolean pending, int pageSize) {
        requireNoS3OuterTransaction();
        Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        Map<Long, String> results = new LinkedHashMap<>();
        long after = 0;
        for (;;) {
            List<Long> ids = contractOrderRepository.findScheduledIds(tenant, after, pending ? "PENDING" : "OPEN", pending,
                    PageRequest.of(0, Math.max(1, Math.min(100, pageSize))));
            if (ids.isEmpty()) return results;
            for (Long id : ids) {
                after = id;
                try {
                    ContractOrder route = contractOrderRepository.findByTenantIdAndId(tenant, id).orElse(null);
                    if (route == null) { results.put(id, "SKIPPED"); continue; }
                    PreparedQuotes prepared = prepareQuotesS3(Collections.singletonList(route));
                    int changed = executeOrdinaryUnitS3(id, route.getUserId(), route.getFundingSource(), prepared, pending);
                    results.put(id, changed == 0 ? "SKIPPED" : pending ? "OPENED" : "CLOSED");
                } catch (RuntimeException failure) {
                    results.put(id, "RETRY:" + failure.getClass().getSimpleName());
                }
            }
        }
    }

    /** One physical transaction for the complete tenant/user/fundingSource portfolio, never per position. */
    public int checkAndForceClosePortfolioS3(Long userId, String fundingSource) {
        requireS3SchedulingReady();
        List<ContractOrder> route = contractOrderRepository.findOpenPortfolio(
                com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, fundingSource);
        return executeForceCloseUnitS3(userId, fundingSource, prepareQuotesS3(route));
    }

    /** Versioned per-portfolio results; no old public full-tenant contract is silently replaced. */
    public Map<String, String> checkAndForceCloseOrdersS3(int pageSize) {
        requireS3SchedulingReady();
        Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        Map<String, String> results = new LinkedHashMap<>();
        long after = 0;
        for (;;) {
            List<Long> ids = contractOrderRepository.findScheduledIds(tenant, after, "OPEN", false,
                    PageRequest.of(0, Math.max(1, Math.min(100, pageSize))));
            if (ids.isEmpty()) return results;
            for (Long id : ids) {
                after = id;
                ContractOrderRepository.FundingOwner owner = contractOrderRepository.findFundingOwner(tenant, id).orElse(null);
                if (owner == null) continue;
                String key = owner.getUserId() + ":" + (owner.getFundingSource() == null ? "LEGACY" : owner.getFundingSource());
                if (results.containsKey(key)) continue;
                try {
                    List<ContractOrder> route = contractOrderRepository.findOpenPortfolio(tenant, owner.getUserId(), owner.getFundingSource());
                    int closed = executeForceCloseUnitS3(owner.getUserId(), owner.getFundingSource(), prepareQuotesS3(route));
                    results.put(key, closed == 0 ? "SKIPPED" : "CLOSED:" + closed);
                } catch (RuntimeException failure) {
                    results.put(key, "RETRY:" + failure.getClass().getSimpleName());
                }
            }
        }
    }

    /** Immutable candidate vector; only the later current database validation authorizes funding. */
    private static final class PreparedQuotes {
        private final Long tenant;
        private final Map<List<String>, Map<String, Object>> entries;
        private PreparedQuotes(Long tenant, Map<List<String>, Map<String, Object>> entries) {
            this.tenant = tenant;
            this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
        private Map<String, Object> forOrder(ContractOrder order) {
            com.gtcfesk.exchange.tenant.TenantContext.require(tenant);
            Map<String, Object> quote = entries.get(quoteRoute(order));
            if (quote == null) throw new BusinessException("组合报价不完整，整组合暂缓结算");
            if (!com.gtcfesk.exchange.market.QuoteState.valid(quote))
                throw new BusinessException("组合报价时间或价格无效，整组合暂缓结算");
            decimal(quote, "price", "available", quote.containsKey("executionExpiresAt") ? "executionExpiresAt" : "expiresAt");
            decimal(quote, "quoteToUsdRate", "conversionAvailable", "conversionExpiresAt");
            return quote;
        }
    }

    private PreparedQuotes prepareQuotesS3(List<ContractOrder> orders) {
        requireNoS3OuterTransaction();
        Map<List<String>, Map<String, Object>> vector = new LinkedHashMap<>();
        for (ContractOrder order : orders) {
            requireS3SupportedOrder(order);
            List<String> key = quoteRoute(order);
            if (vector.containsKey(key)) continue;
            Map<String, Object> snapshot = s3SchedulingEnabled ? quoteAuthority.prepare(order.getSymbol()) : quotes.snapshotPrice(order.getSymbol());
            Map<String, Object> conversion;
            if(s3SchedulingEnabled) {
                if(!Objects.equals(order.getQuoteCurrency(),snapshot.get("configuredQuoteCurrency")) || !Objects.equals(order.getQuoteSource(),snapshot.get("configuredSource"))) throw new BusinessException("S3结算币种与权威配置不符");
                conversion=quoteAuthority.conversion(snapshot,order.getQuoteCurrency(),order.getQuoteSource());
            } else conversion=quotes.contractConversion(order.getQuoteCurrency(),order.getQuoteSource());
            if (snapshot == null || conversion == null) throw new BusinessException("组合报价缺失，整组合暂缓结算");
            Map<String, Object> quote = new HashMap<>(snapshot);
            quote.putAll(conversion);
            if (order.getFxBaseCurrency() != null && !"USD".equals(order.getFxBaseCurrency()) && !"USD".equals(order.getQuoteCurrency())) {
                Map<String, Object> base = s3SchedulingEnabled ? quoteAuthority.conversion(snapshot,order.getFxBaseCurrency(),"yahoo")
                    : quotes.contractConversion(order.getFxBaseCurrency(), "yahoo");
                if(s3SchedulingEnabled) {
                    if(!Objects.equals(order.getFxBaseCurrency(),snapshot.get("configuredBaseCurrency"))) throw new BusinessException("S3保证金币种与权威配置不符");
                    quote.put("marginConversionRequired",true);quote.put("marginConversionCurrency",order.getFxBaseCurrency());
                    quote.put("marginConversionReceipt",base.get("conversionReceipt"));
                }
                quote.put("marginBaseToUsdRate", base.get("quoteToUsdRate"));
                quote.put("marginRateAvailable", base.get("conversionAvailable"));
                quote.put("marginRateExpiresAt", base.get("conversionExpiresAt"));
            }
            vector.put(Collections.unmodifiableList(new ArrayList<>(key)), Collections.unmodifiableMap(quote));
        }
        PreparedQuotes prepared = new PreparedQuotes(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), vector);
        for (ContractOrder order : orders) prepared.forOrder(order);
        return prepared;
    }

    private void requireS3SupportedOrder(ContractOrder order) {
        String source=order.getFundingSource();
        if(s3SchedulingEnabled && (source!=null&&!"CONTRACT".equals(source)&&!"TRIAL".equals(source)
                || "CONTRACT".equals(source)&&trial(order.getTrialReserved()).signum()!=0
                || !"CONTRACT".equals(source)&&trialFunds==null))
            throw new BusinessException("S3资金来源无效，拒绝结算");
    }

    private static BigDecimal decimal(Map<String, Object> quote, String field, String available, String expiry) {
        Object raw = quote.get(field);
        if (!Boolean.TRUE.equals(quote.get(available)) || !(raw instanceof Number)
                || com.gtcfesk.exchange.market.QuoteState.time(quote.get(expiry)) <= System.currentTimeMillis())
            throw new BusinessException("组合报价缺失、无效或已过期，整组合暂缓结算");
        try {
            BigDecimal value = new BigDecimal(raw.toString());
            if (value.signum() <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new BusinessException("组合报价无效，整组合暂缓结算");
        }
    }

    private TransactionTemplate fundingUnitS3(String source) {
        TransactionTemplate transaction=new TransactionTemplate(transactionManager);
        // Newly enabled trial/mixed units require current optional-anchor discovery, without a missing-row gap.
        // The already-certified explicit cash path retains its original physical isolation.
        if(s3SchedulingEnabled && !"CONTRACT".equals(source))transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private AssetAccount lockUnitFundingS3(Long userId,String source) {
        if (userId == null || userId <= 0) throw new BusinessException("用户无效");
        if (users == null || audit == null) throw new IllegalStateException("S3资金锁或成功审计未配置");
        if(s3SchedulingEnabled) {
            if(trialFunds!=null) {
                if("CONTRACT".equals(source))trialFunds.lockForQuote(userId);
                else trialFunds.lockForQuoteAndPendingExpiry(userId);
            }
            else {users.lockById(userId).orElseThrow(() -> new BusinessException("用户不存在"));assetAccountRepository.lockByUserId(userId);}
        } else lockFunding(userId);
        return assetAccountRepository.findByTenantIdAndUserIdAndCoin(
                com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "CONTRACT")
                .orElseThrow(() -> new BusinessException("合约资产账户不存在"));
    }

    /** One physical funding unit. The optional non-production caller requires committed S2 authority. */
    private int executeOrdinaryUnitS3(Long id, Long userId, String fundingSource, PreparedQuotes prepared, boolean pending) {
        requireNoS3OuterTransaction();
        com.gtcfesk.exchange.tenant.TenantContext.require(prepared.tenant);
        if (fundingSource != null && !"CONTRACT".equals(fundingSource) && !"TRIAL".equals(fundingSource))
            throw new BusinessException("合约资金来源无效");
        TransactionTemplate transaction=fundingUnitS3(fundingSource);
        if(s3SchedulingEnabled&&pending)transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            AssetAccount account = lockUnitFundingS3(userId,fundingSource);
            ContractOrder order = contractOrderRepository.lockOrderById(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).orElse(null);
            if (order == null) return 0;
            if (entityManager != null) entityManager.refresh(order, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
            if (!Objects.equals(userId, order.getUserId()) || !Objects.equals(fundingSource, order.getFundingSource()))
                throw new BusinessException("订单归属或资金来源已变化，请重试");
            if (!(pending ? "PENDING" : "OPEN").equals(order.getStatus())) return 0;
            requireS3SupportedOrder(order);
            Map<String, Object> quote = prepared.forOrder(order);
            if(s3SchedulingEnabled) {
                quoteAuthority.validate(prepared.entries.values());
                TradingSymbol current=currentPreparedInstrument(order.getSymbol(),quote);requireCurrentMarket(current);
                if(pending){
                    try{identityService.requireCurrentTradingApproved(userId);}catch(com.gtcfesk.exchange.common.KycRequiredException denied){return 0;}
                    if(!categories.currentLeverageEnabled(current.getCategory())&&order.getLeverage().compareTo(BigDecimal.ONE)>0)return 0;
                }
                // Expiry cancellation and the fill are one authorized unit; prelocked pending orders cannot be resurrected.
                if(pending && !"CONTRACT".equals(fundingSource))trialFunds.lock(userId);
                if(entityManager!=null) entityManager.refresh(order,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
                if(!(pending ? "PENDING" : "OPEN").equals(order.getStatus())) return 0;
            }
            BigDecimal price = new BigDecimal(quote.get("price").toString());
            BigDecimal rate = new BigDecimal(quote.get("quoteToUsdRate").toString());
            if (pending) {
                if (!"LIMIT".equals(order.getType()) || !order.isLimitMatchEnabled() || !s3SchedulingEnabled&&!identityService.canUseTradingFunds(userId)) return 0;
                if (trialFunds != null && "TRIAL".equals(fundingSource) && trialFunds.reservationExpired(order.getTrialAllocations())) return 0;
                TradingSymbol symbol = tradingSymbolRepository.findByTenantIdAndSymbol(prepared.tenant, order.getSymbol()).orElse(null);
                if (symbol == null || !Boolean.TRUE.equals(symbol.getIsEnabled())
                        || !s3SchedulingEnabled&&!categories.leverageEnabled(symbol.getCategory()) && order.getLeverage().compareTo(BigDecimal.ONE) > 0) return 0;
                if (order.getPrice() == null || order.getPrice().signum() <= 0
                        || "BUY".equals(order.getSide()) && price.compareTo(order.getPrice()) > 0
                        || "SELL".equals(order.getSide()) && price.compareTo(order.getPrice()) < 0) return 0;
                if (order.getLotSize() == null) {
                    order.setStatus("OPEN"); order.setOpenPrice(price); order.setCurrentPrice(price); order.setOpenTime(LocalDateTime.now());
                    contractOrderRepository.save(order);
                } else {
                    boolean forex = order.getFxBaseCurrency() != null;
                    BigDecimal marginRate = forex ? "USD".equals(order.getFxBaseCurrency()) ? BigDecimal.ONE
                            : "USD".equals(order.getQuoteCurrency()) ? price
                            : decimal(quote, "marginBaseToUsdRate", "marginRateAvailable", "marginRateExpiresAt") : rate;
                    QuantityRules.notional(order.getQuantity(), order.getLotSize(), price, rate, order.getMinOrderNotional());
                    BigDecimal margin = calculateMargin(order.getQuantity(), order.getLotSize(), forex ? BigDecimal.ONE : price, order.getLeverage(), marginRate);
                    BigDecimal difference = margin.subtract(order.getMargin());
                    BigDecimal balance = trialFunds == null ? account.getAvailable() : fundingSource == null
                            ? trialFunds.tradingBalance(userId, account.getAvailable()) : trialFunds.selectedBalance(userId, account.getAvailable(), fundingSource);
                    if (difference.signum() > 0 && balance.compareTo(difference) < 0) return 0;
                    if (trialFunds != null) {
                        com.gtcfesk.exchange.activity.TrialFunds.Reservation resized = trialFunds.resize(userId, account,
                                order.getMargin().add(order.getFee()), margin.add(order.getFee()), trial(order.getTrialReserved()),
                                order.getTrialAllocations(), fundingSource, "CONTRACT", "CONTRACT_RESIZE:" + id);
                        order.setTrialReserved(resized.trial); order.setTrialAllocations(resized.allocations);
                    } else {
                        account.setAvailable(account.getAvailable().subtract(difference)); account.setFrozen(account.getFrozen().add(difference));
                        assetAccountRepository.save(account);
                    }
                    order.setMargin(margin); order.setMarginConversionRate(marginRate); order.setStatus("OPEN");
                    order.setOpenPrice(price); order.setCurrentPrice(price); order.setOpenTime(LocalDateTime.now());
                    contractOrderRepository.save(order);
                }
                auditOrder("S3_CONTRACT_FILL", order, quote);
            } else {
                if (!shouldCloseS3(order, price)) return 0;
                settleLockedOrder(order, account, price, rate);
                auditOrder("S3_CONTRACT_AUTO_CLOSE", order, quote);
            }
            return 1;
        });
    }

    private boolean shouldCloseS3(ContractOrder order, BigDecimal price) {
        boolean buy = "BUY".equals(order.getSide());
        return order.getStopLoss() != null && order.getStopLoss().signum() > 0
                && (buy ? price.compareTo(order.getStopLoss()) <= 0 : price.compareTo(order.getStopLoss()) >= 0)
                || order.getTakeProfit() != null && order.getTakeProfit().signum() > 0
                && (buy ? price.compareTo(order.getTakeProfit()) >= 0 : price.compareTo(order.getTakeProfit()) <= 0);
    }

    private int executeForceCloseUnitS3(Long userId, String fundingSource, PreparedQuotes prepared) {
        requireNoS3OuterTransaction();
        com.gtcfesk.exchange.tenant.TenantContext.require(prepared.tenant);
        if (fundingSource != null && !"CONTRACT".equals(fundingSource) && !"TRIAL".equals(fundingSource))
            throw new BusinessException("合约资金来源无效");
        return fundingUnitS3(fundingSource).execute(status -> {
            AssetAccount account = lockUnitFundingS3(userId,fundingSource);
            List<ContractOrder> portfolio = contractOrderRepository.lockOpenPortfolio(prepared.tenant, userId, fundingSource);
            if (portfolio.isEmpty()) return 0;
            // Locking/current read after waiting for user; refresh removes any earlier managed snapshot.
            BigDecimal marginAndLegacyFee = BigDecimal.ZERO, profit = BigDecimal.ZERO;
            for (ContractOrder order : portfolio) {
                if (entityManager != null) entityManager.refresh(order, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
                if (!Objects.equals(userId, order.getUserId()) || !Objects.equals(fundingSource, order.getFundingSource()) || !"OPEN".equals(order.getStatus()))
                    throw new BusinessException("组合仓位已变化，请重试");
                requireS3SupportedOrder(order);
                Map<String, Object> quote = prepared.forOrder(order);
                BigDecimal price = new BigDecimal(quote.get("price").toString()), rate = new BigDecimal(quote.get("quoteToUsdRate").toString());
                marginAndLegacyFee = marginAndLegacyFee.add(trial(order.getMargin())).add(order.getLotSize() == null ? trial(order.getFee()) : BigDecimal.ZERO);
                profit = profit.add(money(calculateQuoteProfit(order, price).multiply(rate), RoundingMode.HALF_UP));
            }
            if(s3SchedulingEnabled) {
                quoteAuthority.validate(prepared.entries.values());
                for(ContractOrder order:portfolio)requireCurrentMarket(currentPreparedInstrument(order.getSymbol(),prepared.forOrder(order)));
            }
            BigDecimal selected = trialFunds == null ? account.getAvailable() : fundingSource == null
                    ? trialFunds.tradingBalance(userId, account.getAvailable()) : trialFunds.selectedBalance(userId, account.getAvailable(), fundingSource);
            if (profit.signum() >= 0 || profit.negate().compareTo(selected.add(marginAndLegacyFee)) < 0) return 0;
            // Entire refreshed vector validated above, before any position/cash/trial settlement.
            for (ContractOrder order : portfolio) {
                Map<String, Object> quote = prepared.forOrder(order);
                settleLockedOrder(order, account, new BigDecimal(quote.get("price").toString()), new BigDecimal(quote.get("quoteToUsdRate").toString()));
                auditOrder("S3_CONTRACT_FORCE_CLOSE", order, quote);
            }
            if (account.getAvailable().signum() < 0) {
                account.setAvailable(BigDecimal.ZERO); assetAccountRepository.save(account);
            }
            audit.record(null, prepared.tenant, null, "S3_CONTRACT_FORCE_PORTFOLIO", userId + ":" + fundingSource,
                    "SUCCESS", "orders=" + portfolio.size() + "; available=" + account.getAvailable() + "; frozen=" + account.getFrozen(), null);
            return portfolio.size();
        });
    }

    private static Map<String, Object> settlementEvidence(ContractOrder order) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", order.getStatus()); evidence.put("symbol", order.getSymbol());
        evidence.put("openPrice", order.getOpenPrice()); evidence.put("closePrice", order.getClosePrice());
        evidence.put("margin", order.getMargin()); evidence.put("fee", order.getFee()); evidence.put("profit", order.getProfit());
        evidence.put("marginConversionRate", order.getMarginConversionRate()); evidence.put("settlementConversionRate", order.getSettlementConversionRate());
        evidence.put("trialReserved", order.getTrialReserved());
        return evidence;
    }

    private void auditOrder(String action, ContractOrder order, Map<String, Object> quote) {
        // Required Spring injection; null only keeps directly constructed historical fixtures compatible.
        if (audit == null) return;
        String detail = "user=" + order.getUserId() + "; source=" + order.getFundingSource() + "; quote=" + quote;
        if (com.gtcfesk.exchange.control.ControlIdentity.current() == null)
            audit.record(null, com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), null, action, String.valueOf(order.getId()), "SUCCESS", detail, null);
        else audit.recordCurrent(action, String.valueOf(order.getId()), detail, null);
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
        quotes.requireMarketOpen(symbol);
        BigDecimal price = quotes.freshPrice(symbol);
        if (price == null || price.signum() <= 0) {
            throw new BusinessException("行情暂不可用或报价已过期，请稍后重试");
        }
        return price;
    }

    /** 计算订单的实时盈亏。 */
    private BigDecimal conversionRate(String currency,String source) {
        return com.gtcfesk.exchange.market.QuoteCurrencyConversion.fixed(currency)?BigDecimal.ONE:quotes.requireContractConversionRate(currency,source);
    }
    private BigDecimal calculateProfit(ContractOrder order, BigDecimal currentPrice) {
        return money(calculateQuoteProfit(order,currentPrice).multiply(conversionRate(order.getQuoteCurrency(),order.getQuoteSource())),RoundingMode.HALF_UP);
    }
    private BigDecimal calculateQuoteProfit(ContractOrder order, BigDecimal currentPrice) {
        return ContractValuation.quoteProfit(order, currentPrice);
    }
}
