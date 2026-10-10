package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.activity.*;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.market.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.user.KycIdentityService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Offline H2 physical-commit / fault checkpoints with synthetic existing-shape quote maps.
 * Private prepared cores are intentionally invoked by reflection: the public S3 gate stays
 * fail-closed until S2 authority is integrated. These tests do NOT certify S2 or MySQL RR.
 */
@SpringJUnitConfig(ContractSchedulingS3Test.Config.class)
class ContractSchedulingS3Test {
    @Configuration
    @EnableJpaRepositories(repositoryFactoryBeanClass=com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean.class,
            basePackages={"com.gtcfesk.exchange.repository", "com.gtcfesk.exchange.activity", "com.gtcfesk.exchange.admin"})
    static class Config {
        @Bean DataSource dataSource() throws Exception {
            String port = System.getProperty("activity.test.mysqlPort");
            if (port == null) return new DriverManagerDataSource("jdbc:h2:mem:s3_contract_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
            if (!"33419".equals(port)) throw new IllegalArgumentException("Contract S3 requires its own MySQL port 33419");
            String expectedUuid = System.getProperty("contract.test.mysqlUuid");
            if (expectedUuid == null || !expectedUuid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException("Exact contract.test.mysqlUuid required before creating schema");
            String password = System.getenv("ACTIVITY_TEST_MYSQL_PASSWORD");
            if (password == null || password.isEmpty()) throw new IllegalArgumentException("Own S3 MySQL password environment required");
            DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33419/activity_test"
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8", "root", password);
            // Read-only identity guard precedes Hibernate's create-drop. Never accept S2/main or guessed fixture identity.
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                    ResultSet identity = statement.executeQuery("SELECT @@server_uuid, DATABASE()")) {
                if (!identity.next() || !expectedUuid.equalsIgnoreCase(identity.getString(1)) || !"activity_test".equals(identity.getString(2)))
                    throw new IllegalStateException("Own Contract S3 MySQL UUID/database mismatch; schema creation refused");
            }
            return dataSource;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.gtcfesk.exchange.entity", "com.gtcfesk.exchange.activity", "com.gtcfesk.exchange.admin");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties properties = new Properties();
            properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
            properties.setProperty("hibernate.physical_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            factory.setJpaProperties(properties);
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }

    @Autowired ContractOrderRepository orders;
    @Autowired AssetAccountRepository assets;
    @Autowired UserAccountRepository users;
    @Autowired TradingSymbolRepository symbols;
    @Autowired TrialAccountRepository trials;
    @Autowired TrialGrantRepository grants;
    @Autowired TrialLedgerRepository ledger;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory factory;
    @Autowired DataSource dataSource;
    ContractOrderService service;
    ForexQuoteMarketService quotes;
    KycIdentityService identity;
    ControlAuditService audit;
    TrialFunds funds;
    EntityManager entityManager;
    JdbcTemplate database;
    TenantContext.Scope scope;
    String symbol;
    Map<String, Map<String, Object>> quoteInputs;
    Map<String, Object> conversion;
    AtomicInteger auditCalls;
    int failAuditAt;

    @BeforeEach void setup() {
        scope = TenantContext.open(1L);
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
        database = new JdbcTemplate(dataSource);
        database.execute("CREATE TABLE IF NOT EXISTS s3_contract_success_audit (id BIGINT AUTO_INCREMENT PRIMARY KEY, action VARCHAR(128), object_ref VARCHAR(255))");
        database.update("DELETE FROM s3_contract_success_audit");
        auditCalls = new AtomicInteger(); failAuditAt = 0;
        quotes = mock(ForexQuoteMarketService.class);
        identity = mock(KycIdentityService.class);
        when(identity.canUseTradingFunds(anyLong())).thenReturn(true);
        audit = mock(ControlAuditService.class);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "success audit must share money transaction");
            database.update("INSERT INTO s3_contract_success_audit(action, object_ref) VALUES (?, ?)", (Object) call.getArgument(3), (Object) call.getArgument(4));
            if (auditCalls.incrementAndGet() == failAuditAt) throw new IllegalStateException("synthetic audit failure");
            return null;
        }).when(audit).record(nullable(Long.class), anyLong(), nullable(String.class), anyString(), anyString(), eq("SUCCESS"), anyString(), nullable(String.class));
        MarketCategoryService categories = mock(MarketCategoryService.class);
        when(categories.leverageEnabled(anyString())).thenReturn(true);
        service = new ContractOrderService(identity, orders, assets, symbols, quotes, manager, categories);
        ReflectionTestUtils.setField(service, "configs", mock(com.gtcfesk.exchange.admin.SystemConfigService.class));
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "users", users);
        ReflectionTestUtils.setField(service, "audit", audit);
        funds = new TrialFunds(trials, grants, ledger, users, assets, identity);
        ReflectionTestUtils.setField(funds, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "trialFunds", funds);
        symbol = "S3C" + UUID.randomUUID().toString().substring(0, 8);
        TradingSymbol config = new TradingSymbol(); config.setSymbol(symbol); config.setName("Synthetic S3 contract");
        config.setCategory("Crypto"); config.setSourceCategory("Crypto"); config.setMarketSource("binance");
        config.setBaseCurrency("BTC"); config.setQuoteCurrency("USD"); config.setIsEnabled(true); symbols.saveAndFlush(config);
        quoteInputs = new HashMap<>(); quoteInputs.put(symbol, quote("110"));
        conversion = new HashMap<>(); conversion.put("quoteToUsdRate", BigDecimal.ONE);
        conversion.put("conversionAvailable", true); conversion.put("conversionExpiresAt", Long.MAX_VALUE); conversion.put("conversionMode", "LIVE_CONTRACT");
        when(quotes.snapshotPrice(anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "quote preparation must be outside money transaction");
            return quoteInputs.get(call.getArgument(0));
        });
        when(quotes.contractConversion(anyString(), nullable(String.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "conversion preparation must be outside money transaction");
            return conversion;
        });
    }
    @AfterEach void clear() { if (scope != null) scope.close(); TenantContext.clear(); }
    static BigDecimal number(String value) { return new BigDecimal(value); }
    static void money(String expected, BigDecimal actual) { assertEquals(0, number(expected).compareTo(actual)); }
    static Map<String, Object> quote(String price) {
        Map<String, Object> result = new HashMap<>(); result.put("price", number(price)); result.put("available", true);
        result.put("timestamp", System.currentTimeMillis()); result.put("expiresAt", System.currentTimeMillis() + 60000);
        result.put("quoteVersion", 1L); result.put("marketRevision", 1L); result.put("epoch", "synthetic-existing-shape");
        return result;
    }
    Long user(String available, String frozen) {
        UserAccount user = new UserAccount(); user.setEmail("s3-contract-" + UUID.randomUUID() + "@example.invalid");
        user.setPasswordHash("not-a-login"); users.saveAndFlush(user);
        AssetAccount account = new AssetAccount(); account.setUserId(user.getId()); account.setCoin("CONTRACT");
        account.setAvailable(number(available)); account.setFrozen(number(frozen)); assets.saveAndFlush(account); return user.getId();
    }
    ContractOrder order(Long user, String source) {
        ContractOrder order = new ContractOrder(); order.setUserId(user); order.setSymbol(symbol); order.setFundingSource(source);
        order.setSide("BUY"); order.setType("MARKET"); order.setStatus("OPEN"); order.setQuantity(BigDecimal.ONE);
        order.setOpenPrice(number("100")); order.setLeverage(BigDecimal.ONE); order.setMargin(number("10")); order.setFee(BigDecimal.ZERO);
        order.setQuoteCurrency("USD"); order.setQuoteSource("binance"); order.setTrialReserved(BigDecimal.ZERO);
        order.setProfit(BigDecimal.ZERO); order.setTakeProfit(number("105")); return orders.saveAndFlush(order);
    }
    ContractOrder load(ContractOrder order) { return orders.findByTenantIdAndId(1L, order.getId()).get(); }
    AssetAccount account(Long user) { return assets.findByTenantIdAndUserIdAndCoin(1L, user, "CONTRACT").get(); }
    Object prepared(ContractOrder... positions) { return ReflectionTestUtils.invokeMethod(service, "prepareQuotesS3", Arrays.asList(positions)); }
    int ordinary(ContractOrder order, Object prepared, boolean pending) {
        return ReflectionTestUtils.invokeMethod(service, "executeOrdinaryUnitS3", order.getId(), order.getUserId(), order.getFundingSource(), prepared, pending);
    }
    int force(Long user, String source, Object prepared) { return ReflectionTestUtils.invokeMethod(service, "executeForceCloseUnitS3", user, source, prepared); }
    int auditRows() { return database.queryForObject("SELECT COUNT(*) FROM s3_contract_success_audit", Integer.class); }
    void unchanged(Long user, String available, String frozen, ContractOrder... positions) {
        money(available, account(user).getAvailable()); money(frozen, account(user).getFrozen());
        for (ContractOrder position : positions) { ContractOrder actual = load(position); assertEquals("OPEN", actual.getStatus()); assertNull(actual.getClosePrice()); assertNull(actual.getCurrentPrice()); }
    }

    @Test void publicS3EntryPointsRemainFailClosedWithoutS2Authority() {
        assertThrows(BusinessException.class, () -> service.matchPendingLimitOrdersS3(10));
        assertThrows(BusinessException.class, () -> service.checkAndAutoCloseOrdersS3(10));
        assertThrows(BusinessException.class, () -> service.checkAndForceCloseOrdersS3(10));
        assertThrows(BusinessException.class, () -> service.checkAndForceClosePortfolioS3(1L, "CONTRACT"));
        verifyNoInteractions(quotes, audit); assertEquals(0, auditRows());
    }

    @Test void ordinaryUnitsPhysicallyCommitIndependentlyAndDuplicateDoesNotAuditAgain() {
        Long good = user("90", "10"), bad = user("50", "5");
        ContractOrder one = order(good, "CONTRACT"), two = order(bad, "CONTRACT"); Object first = prepared(one), second = prepared(two);
        assertEquals(1, ordinary(one, first, false));
        assertThrows(BusinessException.class, () -> ordinary(two, second, false));
        assertEquals("CLOSED", load(one).getStatus()); money("110", account(good).getAvailable()); money("0", account(good).getFrozen());
        unchanged(bad, "50", "5", two); assertEquals(1, auditRows());
        assertEquals(0, ordinary(one, first, false)); assertEquals(1, auditRows());
    }

    @Test void secondForcePositionFailureRollsBackCashOrdersAndFirstSuccessAudit() {
        Long user = user("0", "15"); ContractOrder one = order(user, "CONTRACT"), two = order(user, "CONTRACT");
        quoteInputs.put(symbol, quote("80")); Object prepared = prepared(one, two);
        assertThrows(BusinessException.class, () -> force(user, "CONTRACT", prepared));
        unchanged(user, "0", "15", one, two); assertEquals(0, auditRows());
    }

    @Test void secondAuditFailureRollsBackEntirePortfolio() {
        Long user = user("0", "20"); ContractOrder one = order(user, "CONTRACT"), two = order(user, "CONTRACT");
        quoteInputs.put(symbol, quote("80")); Object prepared = prepared(one, two); failAuditAt = 2;
        assertThrows(IllegalStateException.class, () -> force(user, "CONTRACT", prepared));
        unchanged(user, "0", "20", one, two); assertEquals(0, auditRows()); assertEquals(2, auditCalls.get());
    }

    @Test void finalPortfolioAuditFailureRollsBackUnderwaterWriteAndEveryPosition() {
        Long user = user("0", "20"); ContractOrder one = order(user, "CONTRACT"), two = order(user, "CONTRACT");
        quoteInputs.put(symbol, quote("80")); Object prepared = prepared(one, two); failAuditAt = 3;
        assertThrows(IllegalStateException.class, () -> force(user, "CONTRACT", prepared));
        unchanged(user, "0", "20", one, two); assertEquals(0, auditRows()); assertEquals(3, auditCalls.get());
    }

    @Test void secondTrialLedgerFailureRollsBackGrantTrialCashOrdersAndAudit() {
        Long user = user("0", "0");
        TrialAccount trial = new TrialAccount(); trial.setUserId(user); trial.setFrozen(number("20")); trial.setGranted(number("20")); trial.setTrialEligible(true); trials.saveAndFlush(trial);
        TrialGrant grant = new TrialGrant(); grant.setUserId(user); grant.setRequestKey("synthetic-" + user); grant.setClaimedAt(LocalDateTime.now());
        grant.setExpiresAt(LocalDateTime.now().plusDays(1)); grant.setFrozen(number("20")); grants.saveAndFlush(grant);
        ContractOrder one = order(user, "TRIAL"), two = order(user, "TRIAL");
        for (ContractOrder position : Arrays.asList(one, two)) { position.setTrialReserved(number("10")); position.setTrialAllocations("{\"" + grant.getId() + "\":10}"); orders.saveAndFlush(position); }
        TrialLedgerRepository failingLedger = mock(TrialLedgerRepository.class); AtomicInteger writes = new AtomicInteger();
        when(failingLedger.save(any(TrialLedger.class))).thenAnswer(call -> {
            if (writes.incrementAndGet() == 2) throw new IllegalStateException("synthetic second trial ledger failure");
            return ledger.save((TrialLedger) call.getArgument(0));
        });
        TrialFunds failing = new TrialFunds(trials, grants, failingLedger, users, assets, identity); ReflectionTestUtils.setField(failing, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "trialFunds", failing);
        quoteInputs.put(symbol, quote("80")); Object prepared = prepared(one, two);
        assertThrows(IllegalStateException.class, () -> force(user, "TRIAL", prepared));
        unchanged(user, "0", "0", one, two); money("20", trials.findByTenantIdAndId(1L, user).get().getFrozen());
        money("0", trials.findByTenantIdAndId(1L, user).get().getConsumed()); money("20", grants.findByTenantIdAndId(1L, grant.getId()).get().getFrozen());
        assertEquals(0L, ledger.findByTenantIdAndUserIdOrderByIdDesc(1L, user, PageRequest.of(0, 20)).getTotalElements()); assertEquals(0, auditRows());
    }

    @Test void lockedPortfolioIncludesSameSymbolPositionCreatedAfterPreparation() {
        Long user = user("0", "20"); ContractOrder one = order(user, "CONTRACT"); quoteInputs.put(symbol, quote("80")); Object prepared = prepared(one);
        ContractOrder two = order(user, "CONTRACT");
        assertEquals(2, force(user, "CONTRACT", prepared)); assertEquals("CLOSED", load(one).getStatus()); assertEquals("CLOSED", load(two).getStatus());
        money("0", account(user).getAvailable()); money("0", account(user).getFrozen()); assertEquals(3, auditRows());
        assertEquals(0, force(user, "CONTRACT", prepared)); assertEquals(3, auditRows());
    }

    @Test void newSymbolAfterPreparationRejectsEntireLockedPortfolioBeforeSettlement() {
        Long user = user("0", "20"); ContractOrder one = order(user, "CONTRACT"); quoteInputs.put(symbol, quote("80")); Object prepared = prepared(one);
        ContractOrder two = order(user, "CONTRACT"); two.setSymbol(symbol + "B"); orders.saveAndFlush(two); quoteInputs.put(two.getSymbol(), quote("80"));
        assertThrows(BusinessException.class, () -> force(user, "CONTRACT", prepared)); unchanged(user, "0", "20", one, two); assertEquals(0, auditRows());
    }

    @Test void missingExpiredUnavailableNanOrNonpositiveQuoteRejectsWholePreparedVector() {
        Long user = user("0", "20"); ContractOrder one = order(user, "CONTRACT"), two = order(user, "CONTRACT");
        two.setSymbol(symbol + "B"); orders.saveAndFlush(two); quoteInputs.put(symbol, quote("80"));
        List<Map<String, Object>> invalid = new ArrayList<>(); invalid.add(null);
        Map<String, Object> zero = quote("0"), expired = quote("80"), unavailable = quote("80"), nan = quote("80"), negative = quote("-1");
        expired.put("expiresAt", 1L); unavailable.put("available", false); nan.put("price", Double.NaN);
        Map<String, Object> missingTime = quote("80"), futureTime = quote("80");
        missingTime.remove("timestamp"); futureTime.put("timestamp", System.currentTimeMillis() + 60000);
        invalid.addAll(Arrays.asList(zero, expired, unavailable, nan, negative, missingTime, futureTime));
        for (Map<String, Object> quote : invalid) {
            quoteInputs.put(two.getSymbol(), quote); assertThrows(BusinessException.class, () -> prepared(one, two)); unchanged(user, "0", "20", one, two);
        }
        assertEquals(0, auditRows()); verify(quotes, never()).freshPrice(anyString());
    }

    @Test void preparedVectorCopiesValuesAndPreservesDecimalPrecision() {
        Long user = user("0", "10"); ContractOrder order = order(user, "CONTRACT");
        Map<String, Object> raw = quote("110.1234567890123456"); quoteInputs.put(symbol, raw); Object prepared = prepared(order);
        raw.put("price", number("999")); raw.put("expiresAt", 1L); conversion.put("quoteToUsdRate", number("999"));
        assertEquals(1, ordinary(order, prepared, false)); money("110.1234567890123456", load(order).getClosePrice());
        money("20.1234567890123456", account(user).getAvailable()); money("1", load(order).getSettlementConversionRate()); assertEquals(1, auditRows());
    }

    @Test void outerTransactionIsRejectedBeforeNestedUnitWork() {
        Long user = user("0", "10"); ContractOrder order = order(user, "CONTRACT"); Object prepared = prepared(order);
        new TransactionTemplate(manager).execute(status -> {
            assertThrows(IllegalStateException.class, () -> ordinary(order, prepared, false));
            assertThrows(IllegalStateException.class, () -> service.checkAndAutoCloseOrdersS3(10)); return null;
        });
        unchanged(user, "0", "10", order); assertEquals(0, auditRows());
    }

    @Test void healthyOrdinaryUnitDoesNotPersistDisplayPriceOrAudit() {
        Long user = user("0", "10"); ContractOrder order = order(user, "CONTRACT"); quoteInputs.put(symbol, quote("101"));
        assertEquals(0, ordinary(order, prepared(order), false)); unchanged(user, "0", "10", order); assertEquals(0, load(order).getRowVersion()); assertEquals(0, auditRows());
    }

    ContractOrder pending(Long user) {
        ContractOrder order = order(user, "CONTRACT"); order.setStatus("PENDING"); order.setType("LIMIT"); order.setLimitMatchEnabled(true);
        order.setLotSize(BigDecimal.ONE); order.setLeverage(number("10")); order.setPrice(number("95")); order.setOpenPrice(number("95"));
        return orders.saveAndFlush(order);
    }
    @Test void pendingFillUsesPreparedQuoteAndKeepsResizingOrderAndAuditAtomic() {
        Long user = user("90", "10"); ContractOrder order = pending(user); quoteInputs.put(symbol, quote("90")); Object prepared = prepared(order);
        assertEquals(1, ordinary(order, prepared, true)); assertEquals("OPEN", load(order).getStatus()); money("9", load(order).getMargin());
        money("90", load(order).getOpenPrice()); money("91", account(user).getAvailable()); money("9", account(user).getFrozen()); assertEquals(1, auditRows());
        assertEquals(0, ordinary(order, prepared, true)); assertEquals(1, auditRows());
    }
    @Test void pendingFillAuditFailureRollsBackResizingAndOrder() {
        Long user = user("90", "10"); ContractOrder order = pending(user); quoteInputs.put(symbol, quote("90")); Object prepared = prepared(order); failAuditAt = 1;
        assertThrows(IllegalStateException.class, () -> ordinary(order, prepared, true)); assertEquals("PENDING", load(order).getStatus());
        money("10", load(order).getMargin()); money("90", account(user).getAvailable()); money("10", account(user).getFrozen()); assertEquals(0, auditRows());
    }
}
