package com.gtcfesk.exchange.demo;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.market.QuoteState;
import com.gtcfesk.exchange.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Isolated, fully funded spot practice. No real wallet, settlement or KYC dependency. */
@Service
@RequiredArgsConstructor
@Transactional
public class DemoTradingService {
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;
    public static final BigDecimal SEED = new BigDecimal("100000.00000000");
    public static final BigDecimal FEE = new BigDecimal("0.001");
    private final UserAccountRepository users;
    private final DemoAccountRepository accounts;
    private final DemoOrderRepository orders;
    private final DemoLedgerRepository ledger;
    private final TradingSymbolRepository symbols;
    private final ForexQuoteMarketService quotes;

    // Existing user row serializes first allocation as well as all demo mutations across instances.
    private void lock(Long userId) {
        String status = users.lockById(userId).orElseThrow(() -> fail("用户不存在")).getStatus();
        if (!"normal".equalsIgnoreCase(status) && !"active".equalsIgnoreCase(status))
            throw fail("账户状态异常，暂不可使用模拟账户");
    }
    private static BusinessException fail(String message) { return new BusinessException(message); }
    private DemoAccount account(Long userId) {
        return accounts.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId).orElseThrow(() -> fail("请先开通模拟账户"));
    }
    private void generation(DemoAccount account, int generation) {
        if (account.generation != generation) throw fail("模拟账户已重置，请刷新后重试");
    }
    private void record(DemoAccount account, String type, BigDecimal delta, String orderId) {
        DemoLedger entry = new DemoLedger();
        entry.id = UUID.randomUUID().toString(); entry.userId = account.userId;
        entry.generation = account.generation; entry.type = type; entry.delta = delta;
        entry.balanceAfter = account.cash; entry.orderId = orderId; entry.createdAt = Instant.now();
        ledger.save(entry);
    }
    public DemoAccount initialize(Long userId) {
        lock(userId);
        Optional<DemoAccount> existing = accounts.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId);
        if (existing.isPresent()) return existing.get();
        DemoAccount account = new DemoAccount(); account.userId = userId; account.cash = SEED;
        accounts.save(account); record(account, "SEED", SEED, null);
        return account;
    }
    static boolean supported(TradingSymbol symbol) {
        return Boolean.TRUE.equals(symbol.getIsEnabled()) && "Crypto".equals(symbol.getSourceCategory())
            && "USDT".equalsIgnoreCase(symbol.getQuoteCurrency());
    }
    public List<Map<String, Object>> instruments(Long userId) {
        lock(userId);
        return symbols.findByTenantIdAndIsEnabledTrueOrderBySortOrderDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()).stream().filter(DemoTradingService::supported)
            .map(s -> {
                Map<String, Object> row = new LinkedHashMap<>(); row.put("symbol", s.getSymbol());
                row.put("base", s.getBaseCurrency()); row.put("quote", quote(ForexQuoteMarketService.marketCode(s)));
                return row;
            }).collect(Collectors.toList());
    }
    private Map<String, Object> quote(String code) {
        // Raw source only: never use controlled display prices, random paths or manual settlement prices.
        Map<String, Object> raw = quotes.getPrice(code, "Crypto");
        if (raw == null) raw = Collections.emptyMap();
        Map<String, Object> view = QuoteState.view(raw, 60000);
        if (Boolean.FALSE.equals(raw.get("available"))) view.put("available", false);
        return view;
    }
    private BigDecimal price(String code) {
        Map<String, Object> quote = quote(code);
        if (!Boolean.TRUE.equals(quote.get("available"))) throw fail("行情不可用或已过期，请稍后重试");
        BigDecimal price = new BigDecimal(quote.get("price").toString());
        if (price.compareTo(new BigDecimal("0.000000000001")) < 0 || price.compareTo(new BigDecimal("1000000000000")) >= 0)
            throw fail("行情价格异常");
        return price.setScale(16, RoundingMode.HALF_UP);
    }
    public DemoOrder buy(Long userId, String key, int generation, String symbol, BigDecimal amount) {
        tenantPolicy.requireNewBusiness("simulation");
        lock(userId); DemoAccount account = account(userId); generation(account, generation);
        if (amount == null || amount.scale() > 2 || amount.compareTo(BigDecimal.TEN) < 0 || amount.compareTo(SEED) > 0)
            throw fail("模拟买入金额须为 10 至 100000 USDT，最多两位小数");
        Optional<DemoOrder> existing = orders.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, key);
        if (existing.isPresent()) {
            DemoOrder order = existing.get();
            if (!order.symbol.equals(symbol) || order.amount.compareTo(amount) != 0 || order.generation != generation)
                throw fail("请求编号已用于其他订单");
            return order;
        }
        TradingSymbol config = symbols.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), symbol).filter(DemoTradingService::supported)
            .orElseThrow(() -> fail("该品种暂不支持模拟现货交易"));
        if (orders.findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "OPEN").size() >= 50)
            throw fail("最多同时持有 50 笔模拟仓位");
        BigDecimal openPrice = price(ForexQuoteMarketService.marketCode(config));
        BigDecimal fee = amount.multiply(FEE).setScale(8, RoundingMode.HALF_UP);
        BigDecimal debit = amount.add(fee);
        if (account.cash.compareTo(debit) < 0) throw fail("模拟余额不足（需包含手续费）");
        DemoOrder order = new DemoOrder(); order.id = UUID.randomUUID().toString();
        order.userId = userId; order.requestKey = key; order.generation = generation; order.symbol = symbol;
        order.marketCode = ForexQuoteMarketService.marketCode(config); order.status = "OPEN";
        order.amount = amount; order.openPrice = openPrice; order.openFee = fee;
        order.quantity = amount.divide(openPrice, 16, RoundingMode.DOWN);
        if (order.quantity.signum() <= 0 || order.quantity.precision() > 32) throw fail("金额与行情超出支持的数量范围");
        order.createdAt = Instant.now(); account.cash = account.cash.subtract(debit);
        orders.save(order); accounts.save(account); record(account, "BUY", debit.negate(), order.id);
        return order;
    }
    public DemoOrder close(Long userId, String id, int generation) {
        lock(userId); DemoAccount account = account(userId); generation(account, generation);
        DemoOrder order = orders.findByTenantIdAndIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id, userId).orElseThrow(() -> fail("模拟订单不存在"));
        if (order.generation != generation) throw fail("不能操作已重置账户的订单");
        if ("CLOSED".equals(order.status)) return order; // Retrying a lost response never credits twice.
        BigDecimal price = price(order.marketCode);
        BigDecimal gross = order.quantity.multiply(price).setScale(8, RoundingMode.DOWN);
        BigDecimal fee = gross.multiply(FEE).setScale(8, RoundingMode.HALF_UP);
        BigDecimal credit = gross.subtract(fee);
        account.cash = account.cash.add(credit); order.status = "CLOSED"; order.closePrice = price;
        order.closeFee = fee; order.realizedPnl = credit.subtract(order.amount).subtract(order.openFee);
        order.closedAt = Instant.now(); orders.save(order); accounts.save(account);
        record(account, "SELL", credit, id); return order;
    }
    public DemoAccount reset(Long userId, String key, int generation) {
        lock(userId); DemoAccount account = account(userId);
        if (key.equals(account.lastResetKey)) return account;
        generation(account, generation);
        if (!orders.findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "OPEN").isEmpty())
            throw fail("请先卖出全部模拟持仓再重置");
        if (account.lastResetAt != null && account.lastResetAt.plus(Duration.ofHours(24)).isAfter(Instant.now()))
            throw fail("模拟资金每 24 小时仅可重置一次");
        BigDecimal delta = SEED.subtract(account.cash); account.cash = SEED; account.generation++;
        account.lastResetAt = Instant.now(); account.lastResetKey = key; accounts.save(account);
        record(account, "RESET", delta, null); return account;
    }
    public Map<String, Object> snapshot(Long userId) {
        lock(userId); DemoAccount account = account(userId);
        List<DemoOrder> open = orders.findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "OPEN");
        BigDecimal value = BigDecimal.ZERO, cost = BigDecimal.ZERO; boolean complete = true;
        List<Map<String, Object>> positions = new ArrayList<>();
        for (DemoOrder order : open) {
            Map<String, Object> q = quote(order.marketCode), row = new LinkedHashMap<>();
            row.put("order", order); row.put("quote", q);
            if (Boolean.TRUE.equals(q.get("available"))) {
                BigDecimal gross = order.quantity.multiply(new BigDecimal(q.get("price").toString())).setScale(8, RoundingMode.DOWN);
                BigDecimal net = gross.subtract(gross.multiply(FEE).setScale(8, RoundingMode.HALF_UP));
                value = value.add(net); row.put("netValue", net);
                row.put("unrealizedPnl", net.subtract(order.amount).subtract(order.openFee));
            } else complete = false;
            cost = cost.add(order.amount).add(order.openFee); positions.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("mode", "DEMO");
        result.put("account", account); result.put("positions", positions); result.put("feeRate", FEE);
        result.put("valuationComplete", complete); result.put("equity", complete ? account.cash.add(value) : null);
        result.put("unrealizedPnl", complete ? value.subtract(cost) : null);
        result.put("serverTime", Instant.now()); return result;
    }
    public Page<DemoOrder> history(Long userId, int page) {
        lock(userId); return orders.findByTenantIdAndUserIdAndStatus(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, "CLOSED", page(page));
    }
    public Page<DemoLedger> ledger(Long userId, int page) {
        lock(userId); return ledger.findByTenantIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, page(page));
    }
    private Pageable page(int page) {
        if (page < 0 || page > 10000) throw fail("分页参数无效");
        return PageRequest.of(page, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
