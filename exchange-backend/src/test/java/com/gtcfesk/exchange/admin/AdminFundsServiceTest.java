package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.admin.dto.FundsLedgerQuery;
import com.gtcfesk.exchange.simulation.AdminReadRoutes;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminFundsServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AdminFundsService service;
    private TenantContext.Scope tenant;
    private static final String TIME = "'2026-10-21 12:00:00'";

    @BeforeEach void setup() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("funds-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE").build();
        jdbc = new JdbcTemplate(database); service = new AdminFundsService(jdbc); tenant = TenantContext.open(1L);
        jdbc.execute("create table user_account(tenant_id bigint,id bigint,email varchar(254),nickname varchar(100),phone varchar(32),remark varchar(500),parent_user_id bigint)");
        jdbc.execute("create table kyc_record(tenant_id bigint,user_id bigint,real_name varchar(100))");
        jdbc.execute("create table deposit_record(tenant_id bigint,id bigint,user_id bigint,status varchar(20),amount decimal(32,16),currency varchar(10),original_amount decimal(32,16),exchange_rate decimal(32,16),fee_amount decimal(32,16),account_type varchar(20),order_no varchar(64),remark varchar(500),reviewed_by_name varchar(100),created_by_name varchar(100),credited_at timestamp,reviewed_at timestamp,updated_at timestamp)");
        jdbc.execute("create table deposit_credit_record(tenant_id bigint,deposit_record_id bigint,user_id bigint,amount_usd decimal(32,16),account_type varchar(20),balance_before decimal(32,16),balance_after decimal(32,16),operator_name varchar(100),credited_at timestamp)");
        jdbc.execute("create table withdraw_record(tenant_id bigint,id bigint,user_id bigint,status varchar(20),account_type varchar(20),amount decimal(32,16),fee decimal(32,16),currency varchar(10),original_amount decimal(32,16),remark varchar(500),updated_at timestamp)");
        jdbc.execute("create table balance_adjustment(tenant_id bigint,id bigint,user_id bigint,changes varchar(4000),reason varchar(500),request_key varchar(64),actor_type varchar(16),actor_id bigint,created_at timestamp)");
        jdbc.execute("create table contract_order(tenant_id bigint,id bigint,user_id bigint,status varchar(20),order_source varchar(24),manual_wallet_enabled boolean,lot_size decimal(32,16),fee decimal(32,16),profit decimal(32,16),funding_source varchar(16),symbol varchar(32),created_at timestamp,close_time timestamp)");
        jdbc.execute("create table option_order(tenant_id bigint,id bigint,user_id bigint,status varchar(20),profit decimal(32,16),funding_source varchar(16),symbol varchar(32),close_time timestamp)");
        jdbc.execute("create table trial_ledger(tenant_id bigint,id bigint,user_id bigint,reason varchar(100),delta decimal(32,16))");
        jdbc.execute("create table transfer_record(tenant_id bigint,id bigint,user_id bigint,from_account varchar(20),to_account varchar(20),amount decimal(32,16),created_at timestamp)");
        jdbc.execute("create table financial_yield_record(tenant_id bigint,id bigint,user_id bigint,status varchar(20),daily_yield decimal(32,16),product_name varchar(100),paid_at timestamp)");
        jdbc.execute("create table financial_order(tenant_id bigint,id bigint,user_id bigint,status varchar(20),penalty_amount decimal(32,16),product_name varchar(100),redeem_time timestamp)");
        jdbc.execute("create table loan_record(tenant_id bigint,id bigint,user_id bigint,status varchar(20),amount decimal(32,16),repayment_amount decimal(32,16),remark varchar(500),approved_at timestamp,actual_repayment_at timestamp)");
        jdbc.update("insert into user_account values(1,101,'alice@example.com','Alice','123456','customer note',42),(1,102,'bob@example.com','Bob','234567',null,43),(2,101,'other@example.com','Other','345678',null,42)");
    }
    @AfterEach void close() { tenant.close(); SecurityContextHolder.clearContext(); database.shutdown(); }
    private Map<String,Object> query() { return service.query(new FundsLedgerQuery()); }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> result) { return (List<Map<String,Object>>) result.get("list"); }
    @SuppressWarnings("unchecked") private void summary(Map<String,Object> result, String income, String expense, String net) {
        Map<String,Object> values = (Map<String,Object>) result.get("summary");
        assertEquals(income, values.get("income")); assertEquals(expense, values.get("expense")); assertEquals(net, values.get("net"));
    }
    private void deposit(long scope, long id, long user, String state, String amount) {
        jdbc.update("insert into deposit_record(tenant_id,id,user_id,status,amount,account_type,updated_at,order_no) values(?,?,?,?,?,'FUND'," + TIME + ",?)", scope, id, user, state, new BigDecimal(amount), "ORDER_" + id);
    }

    @Test void settledSourcesAreNotDuplicatedAndPendingOrdersNeverBecomeCash() {
        deposit(1, 1, 101, "COMPLETED", "100"); deposit(1, 2, 101, "PENDING", "999"); deposit(1, 3, 101, "REJECTED", "999");
        jdbc.update("insert into deposit_credit_record values(1,1,101,100,'FUND',10,110,'Admin'," + TIME + ")");
        jdbc.update("insert into withdraw_record values(1,1,101,'COMPLETED','FUND',30,2,'USD',30,'done'," + TIME + "),(1,2,101,'APPROVED','FUND',999,0,'USD',999,'pending'," + TIME + ")");
        Map<String,Object> result = query(); assertEquals(3L, result.get("total")); summary(result,"100","32","68");
        Map<String,Object> deposit = rows(result).stream().filter(r -> "DEPOSIT".equals(r.get("type"))).findFirst().get();
        assertEquals("10",deposit.get("balanceBefore")); assertEquals("110",deposit.get("balanceAfter")); assertEquals("Admin",deposit.get("operator"));
    }
    @Test void adjustmentAccountsAreSplitWithExactDecimalsAndCancelledReceiptsExcluded() {
        jdbc.update("insert into balance_adjustment values(1,7,101,'FUND:10.1->25.12;delta=15.02;CONTRACT:5->3;delta=-2;OPTION:0->0;delta=0','quick edit','request_123456789','ADMIN',9," + TIME + "),(1,8,101,'CANCELLED','cancelled','request_cancelled','ADMIN',9," + TIME + ")");
        Map<String,Object> result = query(); assertEquals(2L,result.get("total")); summary(result,"15.02","2","13.02");
        Map<String,Object> fund = rows(result).stream().filter(r -> "FUND".equals(r.get("accountType"))).findFirst().get();
        assertEquals("ADMIN_IN",fund.get("type")); assertEquals("10.1",fund.get("balanceBefore")); assertEquals("25.12",fund.get("balanceAfter")); assertEquals("ADMIN:9",fund.get("operator"));
    }
    @Test void contractFeesFollowActualProtocolAndTrialLossesDoNotDebitRealAccounts() {
        jdbc.update("insert into contract_order values(1,1,101,'CLOSED','USER',false,1,3,20,'CONTRACT','EURUSD'," + TIME + "," + TIME + "),(1,2,101,'CLOSED','USER',false,null,3,20,null,'EURUSD'," + TIME + "," + TIME + "),(1,3,101,'CLOSED','MANUAL_TEST',false,1,3,999,null,'EURUSD'," + TIME + "," + TIME + "),(1,4,101,'OPEN','USER',false,1,3,999,'CONTRACT','EURUSD'," + TIME + ",null),(1,5,101,'CLOSED','USER',false,1,3,-10,'TRIAL','EURUSD'," + TIME + "," + TIME + ")");
        Map<String,Object> result=query(); assertEquals(3L,result.get("total")); summary(result,"40","3","37");
        assertEquals(1,rows(result).stream().filter(r -> "CONTRACT_FEE".equals(r.get("type"))).count());
        assertTrue(rows(result).stream().allMatch(r -> r.get("balanceBefore")==null && r.get("balanceAfter")==null));
    }
    @Test void legacyTrialCompensationAndInternalTransferNetArePreserved() {
        jdbc.update("insert into option_order values(1,1,101,'CLOSED',-10,null,'EURUSD'," + TIME + "),(1,2,101,'CLOSED',-15,'TRIAL','EURUSD'," + TIME + "),(1,3,101,'CLOSED',8,'TRIAL','EURUSD'," + TIME + ")");
        jdbc.update("insert into trial_ledger values(1,1,101,'OPTION_SETTLE:1',-6)");
        jdbc.update("insert into transfer_record values(1,1,101,'FUND','OPTION',20," + TIME + ")");
        Map<String,Object> result=query(); assertEquals(5L,result.get("total")); summary(result,"34","30","4");
    }
    @Test void depositFeeAndOriginalCurrencyDoNotDoubleSubtractNetCredit() {
        deposit(1,1,101,"COMPLETED","99");
        jdbc.update("update deposit_record set currency='EUR',original_amount=100,exchange_rate=1,fee_amount=1 where id=1");
        Map<String,Object> result=query(); assertEquals(2L,result.get("total")); summary(result,"100","1","99");
        assertTrue(rows(result).stream().allMatch(r -> r.get("balanceBefore")==null && "EUR".equals(r.get("originalCurrency"))));
        FundsLedgerQuery q=new FundsLedgerQuery();q.setCurrency("JPY");assertEquals(0L,service.query(q).get("total"));
    }
    @Test void financialYieldRedemptionFeeLoanDisbursementAndRepaymentUseCommittedTimes() {
        jdbc.update("insert into financial_yield_record values(1,1,101,'PAID',5,'Savings',"+TIME+"),(1,2,101,'PENDING',999,'Savings',null)");
        jdbc.update("insert into financial_order values(1,1,101,'REDEEMED',2,'Savings',"+TIME+")");
        jdbc.update("insert into loan_record values(1,1,101,'COMPLETED',50,55,'loan',"+TIME+","+TIME+"),(1,2,101,'SIGNED',999,999,'unsigned',null,null)");
        Map<String,Object> result=query();assertEquals(4L,result.get("total"));summary(result,"55","57","-2");
    }
    @Test void filtersPaginationSummaryAndLiteralWildcardSearchRemainTenantAndAgentScoped() {
        deposit(1,1,101,"COMPLETED","10");deposit(1,2,101,"COMPLETED","20");deposit(1,3,102,"COMPLETED","30");deposit(2,4,101,"COMPLETED","999");
        FundsLedgerQuery q=new FundsLedgerQuery();q.setSize(1);q.setPage(1);q.setKeyword("ALICE");
        Map<String,Object> result=service.query(q);assertEquals(2L,result.get("total"));assertEquals(1,rows(result).size());summary(result,"30","0","30");
        q.setPage(0);q.setAmountMin(new BigDecimal("15"));q.setAmountMax(new BigDecimal("25"));q.setDirection("IN");q.setAccountType("FUND");q.setType("DEPOSIT");q.setStartTime(java.time.LocalDateTime.of(2026,10,21,0,0));q.setEndTime(java.time.LocalDateTime.of(2026,10,21,23,59));
        assertEquals(1L,service.query(q).get("total"));
        q=new FundsLedgerQuery();q.setKeyword("ORDER_1");assertEquals(1L,service.query(q).get("total"));
        q.setKeyword("%");assertEquals(0L,service.query(q).get("total"));
        jdbc.update("insert into kyc_record values(1,101,'Alice Legal')");q.setKeyword("LEGAL");assertEquals(2L,service.query(q).get("total"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent-42", "",Collections.singletonList(new SimpleGrantedAuthority("ROLE_AGENT"))));
        q=new FundsLedgerQuery();q.setFilterAgentId(43L);result=service.query(q);assertEquals(2L,result.get("total"));summary(result,"30","0","30");
    }
    @Test void controllerRequiresFundsPermissionBeforeReadingAndInvalidRangesFailClosed() {
        AdminPermissionService permissions=mock(AdminPermissionService.class);AdminFundsService target=mock(AdminFundsService.class);
        doThrow(new AccessDeniedException("denied")).when(permissions).require("funds_ledger","view");
        assertThrows(AccessDeniedException.class,()->new AdminFundsController(target,permissions).ledger(new FundsLedgerQuery()));verifyNoInteractions(target);
        FundsLedgerQuery invalidDirection=new FundsLedgerQuery();invalidDirection.setDirection("DROP");assertThrows(IllegalArgumentException.class,()->service.query(invalidDirection));
        FundsLedgerQuery invalid=new FundsLedgerQuery();invalid.setAmountMin(BigDecimal.TEN);invalid.setAmountMax(BigDecimal.ONE);assertThrows(IllegalArgumentException.class,()->service.query(invalid));
        assertEquals("funds_ledger",AdminReadRoutes.permission("GET","/api/admin/funds/ledger"));
        assertEquals("funds_ledger",AdminReadRoutes.permission("GET","/api/admin/user-lookup/funds_ledger"));
        assertThrows(IllegalArgumentException.class,()->AdminReadRoutes.permission("POST","/api/admin/funds/ledger"));
    }
}
