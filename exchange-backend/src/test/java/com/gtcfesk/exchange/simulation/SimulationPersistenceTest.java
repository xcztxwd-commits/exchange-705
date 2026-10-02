package com.gtcfesk.exchange.simulation;

import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.activity.*;
import com.gtcfesk.exchange.trade.*;
import com.gtcfesk.exchange.trade.dto.*;
import com.gtcfesk.exchange.market.MarketCategoryService;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.user.*;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.config.BackendAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import javax.persistence.EntityManager;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:full_demo;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=SimulationPersistenceTest.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class SimulationPersistenceTest {
    @Configuration @org.springframework.boot.autoconfigure.AutoConfigurationPackage(basePackages="com.gtcfesk.exchange.simulation") @EntityScan(basePackageClasses={UserAccount.class,SimulationSeed.class,TrialAccount.class})
    static class Config {}
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    <T> T repo(Class<T> type) { com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean bean=new com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean(type);bean.setEntityManager(em);bean.afterPropertiesSet();return type.cast(bean.getObject()); }
    SimulationEnvironment demoEnvironment() { SimulationEnvironment e=mock(SimulationEnvironment.class);when(e.enabled()).thenReturn(true);return e; }
    KycIdentityService identity() { KycIdentityService service=new KycIdentityService(repo(KycRecordRepository.class));ReflectionTestUtils.setField(service,"simulation",demoEnvironment());return service; }
    void policy(Object s){ReflectionTestUtils.setField(s,"tenantPolicy",mock(com.gtcfesk.exchange.control.TenantPolicyService.class));}
    void audit(Object s,String name){ReflectionTestUtils.setField(s,name,mock(com.gtcfesk.exchange.control.ControlAuditService.class));}
    BigDecimal balance(long id,String coin) { return jdbc.queryForObject("SELECT available FROM asset_account WHERE user_id=? AND coin=?",BigDecimal.class,id,coin); }
    SimulationProvisioner provisioner; JdbcTemplate jdbc;
    @BeforeEach void init() {
        jdbc=new JdbcTemplate(dataSource);
        SimulationEnvironment env=mock(SimulationEnvironment.class);when(env.enabled()).thenReturn(true);
        provisioner=new SimulationProvisioner(env,mock(SimulationGateway.class),jdbc,transactions,mock(ForexQuoteMarketService.class));
        jdbc.update("DELETE FROM simulation_seed");jdbc.update("DELETE FROM asset_account");jdbc.update("DELETE FROM user_account");
    }
    @Test void firstUseSeedsThreeIndependentWalletsWithoutKyc() {
        provisioner.user(7001L);provisioner.user(7001L);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM asset_account WHERE user_id=7001",Integer.class));
        assertEquals(0,new BigDecimal("300000").compareTo(jdbc.queryForObject("SELECT SUM(available) FROM asset_account WHERE user_id=7001",BigDecimal.class)));
        assertEquals("NOT_VERIFIED",jdbc.queryForObject("SELECT kyc_status FROM user_account WHERE id=7001",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kyc_record",Integer.class));
    }
    @Test void concurrentLoginsCannotMintRepeatedSeed() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures=new ArrayList<>();for(int i=0;i<12;i++)futures.add(pool.submit(com.gtcfesk.exchange.tenant.TenantOneFixture.worker(()->{provisioner.user(7002L);return null;})));
            for(Future<?> f:futures)f.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM simulation_seed WHERE user_id=7002",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM asset_account WHERE user_id=7002",Integer.class));
    }
    @Test void returningUserKeepsBalancesAndAnotherUserIsIndependent() {
        provisioner.user(7003L);jdbc.update("UPDATE asset_account SET available=42 WHERE user_id=7003 AND coin='FUND'");
        provisioner.user(7004L);provisioner.user(7003L);
        assertEquals(0,new BigDecimal("42").compareTo(jdbc.queryForObject("SELECT available FROM asset_account WHERE user_id=7003 AND coin='FUND'",BigDecimal.class)));
        assertEquals(0,new BigDecimal("100000").compareTo(jdbc.queryForObject("SELECT available FROM asset_account WHERE user_id=7004 AND coin='FUND'",BigDecimal.class)));
    }
    @Test void transactionFailureCannotLeavePartialSeed() {
        jdbc.execute("ALTER TABLE simulation_seed ADD CONSTRAINT reject_test CHECK(user_id <> 7005)");
        try {assertThrows(Exception.class,()->provisioner.user(7005L));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE id=7005",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM asset_account WHERE user_id=7005",Integer.class));
        } finally {jdbc.execute("ALTER TABLE simulation_seed DROP CONSTRAINT reject_test");}
    }
    @Test void sameTransferEngineMovesOnlyDemoSubwalletsAndRejectsReplay() {
        provisioner.user(7010L);
        TransferController service=new TransferController(repo(UserAccountRepository.class),repo(AssetAccountRepository.class),repo(TransferRecordRepository.class));
        TransferController.TransferRequest request=new TransferController.TransferRequest();
        request.setFromAccount("FUND");request.setToAccount("CONTRACT");request.setAmount(new BigDecimal("25"));request.setRequestId("simulation-transfer");
        for(int i=0;i<2;i++) new TransactionTemplate(transactions).execute(status->service.transfer(new UsernamePasswordAuthenticationToken("7010","x"),request));
        assertEquals(0,new BigDecimal("99975").compareTo(balance(7010,"FUND")));
        assertEquals(0,new BigDecimal("100025").compareTo(balance(7010,"CONTRACT")));
    }
    @Test void sameFinancialEnginePurchaseAndRedeemWithoutKyc() {
        provisioner.user(7011L);
        FinancialProductRepository products=repo(FinancialProductRepository.class);
        FinancialService service=new FinancialService(products,repo(FinancialOrderRepository.class),repo(AssetAccountRepository.class));
        policy(service);audit(service,"audit");ReflectionTestUtils.setField(service,"users",repo(UserAccountRepository.class));
        new TransactionTemplate(transactions).execute(status->{
            FinancialProduct product=new FinancialProduct();product.setName("Simulation product");product.setDailyYieldRate(BigDecimal.ONE);
            product.setRentalFee(BigDecimal.ZERO);product.setMinPurchase(BigDecimal.ONE);product.setMaxPurchase(new BigDecimal("10000"));product.setTermDays(7);product.setPenaltyRate(BigDecimal.ZERO);
            product=products.save(product);
            FinancialOrder order=service.purchaseProduct(7011L,product.getId(),new BigDecimal("100"),"simulation-finance-key");assertEquals("IN_PROGRESS",order.getStatus());
            assertEquals(order.getId(),service.purchaseProduct(7011L,product.getId(),new BigDecimal("100.0"),"simulation-finance-key").getId());
            Long productId=product.getId();assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->service.purchaseProduct(7011L,productId,new BigDecimal("101"),"simulation-finance-key"));
            service.earlyRedeem(7011L,order.getId());return null;
        });
        assertEquals(0,new BigDecimal("100000").compareTo(balance(7011,"FUND")));
    }
    @Test void sameLoanEngineSignsCreditsAndRepaysVirtualLoanWithoutIdentity() {
        provisioner.user(7012L);
        LoanRecordRepository records=repo(LoanRecordRepository.class);LoanSettingRepository settings=repo(LoanSettingRepository.class);
        UserAccountRepository users=repo(UserAccountRepository.class);AssetAccountRepository assets=repo(AssetAccountRepository.class);
        LoanPersonalInfoService personal=new LoanPersonalInfoService(repo(LoanPersonalInfoRepository.class),identity());
        LoanService service=new LoanService(records,settings,users,repo(KycRecordRepository.class),assets,personal);
        ReflectionTestUtils.setField(service,"simulation",demoEnvironment());policy(service);
        audit(service,"audit");LoanReviewService review=new LoanReviewService(records,assets,users,personal);ReflectionTestUtils.setField(review,"audit",mock(com.gtcfesk.exchange.control.ControlAuditService.class));policy(review);ReflectionTestUtils.setField(service,"simulationReview",review);
        Long id=new TransactionTemplate(transactions).execute(status->{
            LoanSetting setting=new LoanSetting();setting.setDays(7);setting.setFreeDays(7);setting.setDailyRate(BigDecimal.ZERO);setting.setOverdueRate(BigDecimal.ZERO);settings.save(setting);
            LoanRecord order=service.createLoan(7012L,new BigDecimal("100"),setting.getId(),"simulation-loan-key");
            assertEquals(order.getId(),service.createLoan(7012L,new BigDecimal("100.00"),setting.getId(),"simulation-loan-key").getId());
            assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->service.createLoan(7012L,new BigDecimal("101"),setting.getId(),"simulation-loan-key"));
            service.signContract(order.getId(),7012L,"SIMULATION-SIGNATURE");assertEquals("APPROVED",order.getStatus());return order.getId();
        });
        assertEquals(0,new BigDecimal("100100").compareTo(balance(7012,"FUND")));
        new TransactionTemplate(transactions).execute(status->{service.earlyRepayment(id,7012L);return null;});
        assertEquals(0,new BigDecimal("100000").compareTo(balance(7012,"FUND")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kyc_record",Integer.class));
    }
    @Test void virtualDepositCreditsImmediatelyAndReplayDoesNotCreditTwice() {
        provisioner.user(7013L);
        ForexQuoteMarketService quotes=mock(ForexQuoteMarketService.class);when(quotes.requireConversionRate("USD","yahoo")).thenReturn(BigDecimal.ONE);
        DepositOrderService service=new DepositOrderService(repo(DepositRecordRepository.class),repo(DepositCreditRecordRepository.class),repo(AssetAccountRepository.class),repo(UserAccountRepository.class),mock(AdminUserRepository.class),new FiatCurrencyService(quotes),mock(BackendAccess.class),new ObjectMapper(),transactions);
        ReflectionTestUtils.setField(service,"simulation",demoEnvironment());policy(service);
        DepositSettingRepository channels=repo(DepositSettingRepository.class);
        ReflectionTestUtils.setField(service,"channels",channels);
        new TransactionTemplate(transactions).execute(status->{DepositSetting channel=new DepositSetting();channel.setType("digital");channel.setNetwork("USDT-PERSISTENCE");channel.setAddress("SIMULATION-PERSISTENCE-ONLY");channel.setEnabled(true);channels.saveAndFlush(channel);return null;});
        audit(service,"controlAudit");DepositOrderRequest request=new DepositOrderRequest();request.type="digital";request.currency="USD";request.amount=new BigDecimal("50");request.network="USDT-PERSISTENCE";request.address="SIMULATION-PERSISTENCE-ONLY";request.idempotencyKey="virtual-deposit";
        DepositRecord record=service.submit(7013L,request);assertEquals("COMPLETED",record.getStatus());service.submit(7013L,request);
        assertEquals(0,new BigDecimal("100050").compareTo(balance(7013,"FUND")));
    }
    @Test void virtualWithdrawalCompletesWithNoFrozenRemainderOrRealPayout() {
        provisioner.user(7014L);
        ForexQuoteMarketService quotes=mock(ForexQuoteMarketService.class);
        WithdrawController service=new WithdrawController(repo(WithdrawRecordRepository.class),repo(AssetAccountRepository.class),repo(UserDigitalAddressRepository.class),repo(UserBankCardRepository.class),new FiatCurrencyService(quotes));
        ReflectionTestUtils.setField(service,"identityService",identity());policy(service);
        ReflectionTestUtils.setField(service,"users",repo(UserAccountRepository.class));
        new TransactionTemplate(transactions).execute(status->{
            UserDigitalAddress address=new UserDigitalAddress();address.setUserId(7014L);address.setCurrency("USDT");address.setAddress("SIMULATION-ONLY");address.setNetwork("USDT-TRC20");repo(UserDigitalAddressRepository.class).save(address);
            Map<String,Object> request=new HashMap<>();request.put("type","digital");request.put("network","USDT-TRC20");request.put("amount","25");request.put("address","SIMULATION-ONLY");request.put("requestId","simulation-withdraw-key");
            assertEquals(200,service.submitWithdraw(new UsernamePasswordAuthenticationToken("7014","x"),request).getStatusCodeValue());
            assertEquals(200,service.submitWithdraw(new UsernamePasswordAuthenticationToken("7014","x"),request).getStatusCodeValue());return null;
        });
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM withdraw_record WHERE user_id=7014",String.class));
        assertEquals(0,BigDecimal.ZERO.compareTo(jdbc.queryForObject("SELECT frozen FROM asset_account WHERE user_id=7014 AND coin='FUND'",BigDecimal.class)));
        assertTrue(balance(7014,"FUND").compareTo(new BigDecimal("99975"))<=0);
    }

    @Test void independentDatabaseWithSameUserIdCannotBeMutated() {
        org.springframework.jdbc.datasource.DriverManagerDataSource realSource=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:sentinel_real;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        JdbcTemplate real=new JdbcTemplate(realSource);real.execute("CREATE TABLE IF NOT EXISTS real_balance(user_id BIGINT PRIMARY KEY,amount DECIMAL(32,16))");
        real.update("MERGE INTO real_balance KEY(user_id) VALUES(7015,42)");
        provisioner.user(7015L);jdbc.update("UPDATE asset_account SET available=0 WHERE user_id=7015");
        assertEquals(0,new BigDecimal("42").compareTo(real.queryForObject("SELECT amount FROM real_balance WHERE user_id=7015",BigDecimal.class)));
    }
    @Test void catalogSyncPreservesIdsUsesFakePaymentDestinationsAndConfigKeys() {
        SimulationGateway gateway=mock(SimulationGateway.class);
        Map<String,Object> catalog=new LinkedHashMap<>();
        for(String table:SimulationCatalogController.TABLES)catalog.put(table,new ArrayList<>());
        Map<String,Object> config=new LinkedHashMap<>();config.put("tenant_id",1L);config.put("id",999L);config.put("config_key","system.timezone");config.put("config_value","UTC");config.put("description","Timezone");
        catalog.put("system_config",Arrays.asList(config));when(gateway.get("/catalog","Bearer fixture")).thenReturn(catalog);
        SimulationProvisioner service=new SimulationProvisioner(demoEnvironment(),gateway,jdbc,transactions,mock(ForexQuoteMarketService.class));
        service.catalog("Bearer fixture");service.catalog("Bearer fixture");
        verify(gateway,times(1)).get("/catalog","Bearer fixture");
        assertEquals("UTC",jdbc.queryForObject("SELECT config_value FROM system_config WHERE config_key='system.timezone'",String.class));
        assertTrue(jdbc.queryForList("SELECT address FROM deposit_setting WHERE type='digital'",String.class).stream().allMatch(address->address.startsWith("SIMULATION-")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_account",Integer.class));
    }

    @Test void allSixMarketCategoriesUseSameContractAndOptionEnginesWithoutKyc() {
        provisioner.user(7016L);
        KycIdentityService kyc=identity();
        AssetAccountRepository assets=repo(AssetAccountRepository.class);
        TrialFunds funds=new TrialFunds(repo(TrialAccountRepository.class),repo(TrialLedgerRepository.class),repo(UserAccountRepository.class),assets,kyc);
        TradingSymbolRepository symbols=repo(TradingSymbolRepository.class);
        ForexQuoteMarketService quotes=mock(ForexQuoteMarketService.class);
        when(quotes.freshPrice(anyString())).thenReturn(new BigDecimal("100"));
        when(quotes.fxMarginRate(anyString(),anyString(),any())).thenReturn(new BigDecimal("100"));
        MarketCategoryService categories=mock(MarketCategoryService.class);when(categories.leverageEnabled(anyString())).thenReturn(true);
        ContractOrderService contracts=new ContractOrderService(kyc,repo(ContractOrderRepository.class),assets,symbols,quotes,transactions,categories);
        OptionOrderService options=new OptionOrderService(kyc,repo(OptionOrderRepository.class),assets,symbols,repo(OptionDurationRepository.class),quotes);
        policy(contracts);policy(options);ReflectionTestUtils.setField(contracts,"trialFunds",funds);ReflectionTestUtils.setField(options,"trialFunds",funds);
        ReflectionTestUtils.setField(contracts,"users",repo(UserAccountRepository.class));ReflectionTestUtils.setField(options,"users",repo(UserAccountRepository.class));
        ReflectionTestUtils.setField(options,"transactionManager",transactions);
        TransactionTemplate transaction=new TransactionTemplate(transactions);
        transaction.execute(status->{
            OptionDuration duration=new OptionDuration();duration.setDuration(60);duration.setLabel("60s");duration.setSortOrder(0);duration.setEnabled(true);duration.setProfitRate(new BigDecimal("0.8"));duration.setLossRate(BigDecimal.ONE);repo(OptionDurationRepository.class).save(duration);
            return null;
        });
        for(String category:Arrays.asList("Crypto","Metal","US","Forex","CFD","Oil")) {
            String symbolName="SIM-"+category;
            transaction.execute(status->{
                TradingSymbol symbol=new TradingSymbol();symbol.setSymbol(symbolName);symbol.setName(category);symbol.setBaseCurrency("EUR");symbol.setQuoteCurrency("USD");symbol.setSourceCategory(category);symbol.setCategory(category);symbol.setMarketSource("yahoo");symbol.setIsEnabled(true);symbol.setLotSize(BigDecimal.ONE);symbol.setFeeMultiplier(BigDecimal.ONE);symbol.setMaxLeverage(new BigDecimal("100"));
                FxContractRules.defaults(symbol);symbols.save(symbol);
                for(String side:Arrays.asList("BUY","SELL")) {
                    CreateContractOrderRequest req=new CreateContractOrderRequest();req.setSymbol(symbolName);req.setSide(side);req.setType("MARKET");req.setQuantity(new BigDecimal("0.01"));req.setLeverage(BigDecimal.TEN);
                    req.setRequestId("simulation-contract-"+category+"-"+side+"-market");
                    ContractOrder order=contracts.createOrder(7016L,req);assertEquals("OPEN",order.getStatus());
                    assertEquals(order.getId(),contracts.createOrder(7016L,req).getId());
                    contracts.closeOrder(7016L,order.getId(),null);assertEquals("CLOSED",order.getStatus());
                    req.setType("LIMIT");req.setPrice(new BigDecimal("90"));req.setRequestId("simulation-contract-"+category+"-"+side+"-limit");ContractOrder pending=contracts.createOrder(7016L,req);assertEquals("PENDING",pending.getStatus());contracts.cancelOrder(7016L,pending.getId());
                }
                return null;
            });
            for(String direction:Arrays.asList("UP","DOWN")) {
                // Commit the expired fixture before the production scheduler opens REQUIRES_NEW.
                Long id=transaction.execute(status->{
                    CreateOptionOrderRequest req=new CreateOptionOrderRequest();req.setSymbol(symbolName);req.setDirection(direction);req.setAmount(BigDecimal.TEN);req.setDuration(60);
                    req.setRequestId("simulation-option-"+category+"-"+direction);
                    OptionOrder order=options.createOrder(7016L,req);assertEquals("TRADING",order.getStatus());
                    assertEquals(order.getId(),options.createOrder(7016L,req).getId());
                    order.setOpenTime(java.time.LocalDateTime.now().minusSeconds(120));repo(OptionOrderRepository.class).save(order);
                    return order.getId();
                });
                options.settleExpiredOrders(Collections.emptyMap());
                assertNotEquals("TRADING",repo(OptionOrderRepository.class).findByTenantIdAndId(1L,id).get().getStatus());
            }
        }
        assertEquals(24,jdbc.queryForObject("SELECT COUNT(*) FROM contract_order WHERE user_id=7016",Integer.class));
        assertEquals(12,jdbc.queryForObject("SELECT COUNT(*) FROM option_order WHERE user_id=7016",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kyc_record WHERE user_id=7016",Integer.class));
        assertEquals(0,BigDecimal.ZERO.compareTo(jdbc.queryForObject("SELECT SUM(frozen) FROM asset_account WHERE user_id=7016",BigDecimal.class)));
    }

}
