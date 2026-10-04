package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.LoanReviewService;
import com.gtcfesk.exchange.admin.WithdrawReviewController;
import com.gtcfesk.exchange.control.ControlAuditLogRepository;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantOneFixture;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated real JPA transactions and audit inserts. Synthetic money only; identity/access guards are separate tests. */
@SpringJUnitConfig(DepositOrderServiceTest.Config.class)
@org.junit.jupiter.api.extension.ExtendWith(TenantOneFixture.class)
class MoneyWriteCheckpointRegressionTest {
    @Autowired PlatformTransactionManager manager;
    @Autowired UserAccountRepository users;
    @Autowired AssetAccountRepository assets;
    @Autowired LoanRecordRepository loans;
    @Autowired LoanSettingRepository settings;
    @Autowired TransferRecordRepository transfers;
    @Autowired WithdrawRecordRepository withdrawals;
    @Autowired UserBankCardRepository bankCards;
    @Autowired ControlAuditLogRepository auditLogs;
    @PersistenceContext EntityManager em;
    Long user;
    @BeforeEach void start() {
        SecurityContextHolder.clearContext();
        UserAccount account=new UserAccount();account.setEmail(UUID.randomUUID()+"@money-checkpoint.invalid");account.setPasswordHash("not-a-login");user=users.saveAndFlush(account).getId();
        for(String coin:Arrays.asList("FUND","CONTRACT")){AssetAccount asset=new AssetAccount();asset.setUserId(user);asset.setCoin(coin);asset.setAvailable(new BigDecimal("FUND".equals(coin)?"1000":"100"));assets.saveAndFlush(asset);}
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    BigDecimal balance(String coin){return assets.findByTenantIdAndUserIdAndCoin(1L,user,coin).orElseThrow().getAvailable();}
    BigDecimal frozen(){return assets.findByTenantIdAndUserIdAndCoin(1L,user,"FUND").orElseThrow().getFrozen();}
    void equalsMoney(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    long audits(String action){return em.createQuery("select count(a) from ControlAuditLog a where a.tenantId=1 and a.actorId is null and a.action=:action and a.detail like :user",Long.class).setParameter("action",action).setParameter("user","%userId="+user+";%").getSingleResult();}
    ControlAuditService audit(){return new ControlAuditService(auditLogs);}
    ControlAuditService failedAudit(){return new ControlAuditService(auditLogs){@Override public void record(Long actor,Long tenant,String session,String action,String object,String outcome,String detail,String reason){throw new IllegalStateException("injected required audit unavailable");}};}
    LoanPersonalInfoService personal() {
        LoanPersonalInfoService service=mock(LoanPersonalInfoService.class);LoanPersonalInfo info=new LoanPersonalInfo();info.setUserId(user);info.setRealName("Owned synthetic identity");info.setIdNumber("NOT-A-REAL-IDENTITY");
        when(service.requireApprovedPersonalInfo(user)).thenReturn(info);when(service.requireApprovedPersonalInfoForFunds(user)).thenReturn(info);return service;
    }
    LoanRecord signedLoan() {
        LoanRecord record=new LoanRecord();record.setUserId(user);record.setStatus("SIGNED");record.setAmount(new BigDecimal("100"));record.setDays(7);record.setFreeDays(7);record.setDailyRate(BigDecimal.ZERO);
        record.setOverdueRate(BigDecimal.ZERO);record.setTotalInterest(BigDecimal.ZERO);record.setRepaymentAmount(new BigDecimal("100"));record.setRealName("Owned synthetic identity");record.setIdNumber("NOT-A-REAL-IDENTITY");return loans.saveAndFlush(record);
    }
    LoanReviewService review(String fault) {
        LoanReviewService service=new LoanReviewService(loans,assets,users,personal()){@Override protected void checkpoint(String stage){if(stage.equals(fault))throw new IllegalStateException("injected "+stage);}};
        ReflectionTestUtils.setField(service,"audit",audit());ReflectionTestUtils.setField(service,"tenantPolicy",mock(TenantPolicyService.class));ReflectionTestUtils.setField(service,"entityManager",em);return service;
    }
    LoanService repayment(String fault) {
        LoanService service=new LoanService(loans,settings,users,mock(KycRecordRepository.class),assets,personal()){@Override protected void checkpoint(String stage){if(stage.equals(fault))throw new IllegalStateException("injected "+stage);}};
        ReflectionTestUtils.setField(service,"audit",audit());ReflectionTestUtils.setField(service,"tenantPolicy",mock(TenantPolicyService.class));ReflectionTestUtils.setField(service,"entityManager",em);return service;
    }
    @Test void everyApprovalAndRepaymentWriteRollsBackAndOriginalStateCanRetry() {
        Long id=signedLoan().getId();
        for(String fault:Arrays.asList("approval-order","approval-account","approval-audit")) {
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->review(fault).approveLoan(id)));
            assertEquals("SIGNED",loans.findByTenantIdAndId(1L,id).orElseThrow().getStatus());equalsMoney("1000",balance("FUND"));assertEquals(0,audits("LOAN_APPROVE"));
        }
        new TransactionTemplate(manager).executeWithoutResult(status->{review(null).approveLoan(id);review(null).approveLoan(id);});equalsMoney("1100",balance("FUND"));assertEquals(1,audits("LOAN_APPROVE"));
        for(String fault:Arrays.asList("repayment-account","repayment-order","repayment-audit")) {
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->repayment(fault).earlyRepayment(id,user)));
            LoanRecord unchanged=loans.findByTenantIdAndId(1L,id).orElseThrow();assertEquals("APPROVED",unchanged.getStatus());assertNull(unchanged.getActualRepaymentAt());equalsMoney("1100",balance("FUND"));assertEquals(0,audits("LOAN_REPAY"));
        }
        new TransactionTemplate(manager).executeWithoutResult(status->{repayment(null).earlyRepayment(id,user);repayment(null).earlyRepayment(id,user);});equalsMoney("1000",balance("FUND"));
        assertEquals("COMPLETED",loans.findByTenantIdAndId(1L,id).orElseThrow().getStatus());assertEquals(1,audits("LOAN_REPAY"));
    }
    @Test void loanCreationSigningAndRejectionIncludeRequiredOrdinaryAudit() {
        LoanSetting setting=new LoanSetting();setting.setDays(7);setting.setFreeDays(7);setting.setDailyRate(BigDecimal.ZERO);setting.setOverdueRate(BigDecimal.ZERO);Long settingId=settings.saveAndFlush(setting).getId();String key="owned-creation-checkpoints";
        for(String fault:Arrays.asList("creation-order","creation-audit")) {
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->repayment(fault).createLoan(user,new BigDecimal("100"),settingId,key)));
            assertFalse(loans.findByTenantIdAndUserIdAndRequestKey(1L,user,key).isPresent());assertEquals(0,audits("LOAN_CREATE"));equalsMoney("1000",balance("FUND"));
        }
        Long id=new TransactionTemplate(manager).execute(status->{LoanService service=repayment(null);LoanRecord record=service.createLoan(user,new BigDecimal("100"),settingId,key);assertEquals(record.getId(),service.createLoan(user,new BigDecimal("100"),settingId,key).getId());return record.getId();});assertEquals(1,audits("LOAN_CREATE"));
        for(String fault:Arrays.asList("signing-order","signing-audit")) {
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->repayment(fault).signContract(id,user,"OWNED-NOT-A-REAL-SIGNATURE")));
            assertEquals("PENDING",loans.findByTenantIdAndId(1L,id).orElseThrow().getStatus());assertEquals(0,audits("LOAN_SIGN"));
        }
        new TransactionTemplate(manager).executeWithoutResult(status->repayment(null).signContract(id,user,"OWNED-NOT-A-REAL-SIGNATURE"));assertEquals(1,audits("LOAN_SIGN"));
        for(String fault:Arrays.asList("rejection-order","rejection-audit")) {
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->review(fault).rejectLoan(id,"Owned synthetic reason")));
            assertEquals("SIGNED",loans.findByTenantIdAndId(1L,id).orElseThrow().getStatus());assertEquals(0,audits("LOAN_REJECT"));equalsMoney("1000",balance("FUND"));
        }
        new TransactionTemplate(manager).executeWithoutResult(status->{review(null).rejectLoan(id,"Owned synthetic reason");review(null).rejectLoan(id,"Owned synthetic reason");});assertEquals(1,audits("LOAN_REJECT"));
    }
    TransferController transfer(String fault) {
        TransferController controller=new TransferController(users,assets,transfers){@Override protected void checkpoint(String stage){if(stage.equals(fault))throw new IllegalStateException("injected "+stage);}};
        ReflectionTestUtils.setField(controller,"em",em);ReflectionTestUtils.setField(controller,"audit",audit());return controller;
    }
    TransferController.TransferRequest transferInput(){TransferController.TransferRequest input=new TransferController.TransferRequest();input.setFromAccount("FUND");input.setToAccount("CONTRACT");input.setAmount(BigDecimal.TEN);input.setRequestId("owned-transfer-checkpoint");return input;}
    @Test void everyTransferWriteRollsBackBothAccountsAndReceipt() {
        TransferController.TransferRequest input=transferInput();
        for(String fault:Arrays.asList("transfer-from-account","transfer-to-account","transfer-receipt","transfer-audit")) {
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->transfer(fault).transfer(new UsernamePasswordAuthenticationToken(user.toString(),null),input)));
            equalsMoney("1000",balance("FUND"));equalsMoney("100",balance("CONTRACT"));assertFalse(transfers.findByTenantIdAndUserIdAndRequestId(1L,user,input.getRequestId()).isPresent());assertEquals(0,audits("TRANSFER"));
        }
        new TransactionTemplate(manager).executeWithoutResult(status->{TransferController service=transfer(null);service.transfer(new UsernamePasswordAuthenticationToken(user.toString(),null),input);service.transfer(new UsernamePasswordAuthenticationToken(user.toString(),null),input);});
        equalsMoney("990",balance("FUND"));equalsMoney("110",balance("CONTRACT"));assertEquals(1,audits("TRANSFER"));
    }
    @Test void unavailableOrdinarySuccessAuditRollsBackInsteadOfSilentlySucceeding() {
        TransferController service=transfer(null);ReflectionTestUtils.setField(service,"audit",failedAudit());TransferController.TransferRequest input=transferInput();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->service.transfer(new UsernamePasswordAuthenticationToken(user.toString(),null),input)));
        equalsMoney("1000",balance("FUND"));equalsMoney("100",balance("CONTRACT"));assertFalse(transfers.findByTenantIdAndUserIdAndRequestId(1L,user,input.getRequestId()).isPresent());assertEquals(0,audits("TRANSFER"));
        LoanReviewService loan=review(null);ReflectionTestUtils.setField(loan,"audit",failedAudit());Long id=signedLoan().getId();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->loan.approveLoan(id)));assertEquals("SIGNED",loans.findByTenantIdAndId(1L,id).orElseThrow().getStatus());equalsMoney("1000",balance("FUND"));assertEquals(0,audits("LOAN_APPROVE"));
    }
    @SuppressWarnings("unchecked") <T> T proxy(T target) {
        ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)proxy.getProxy();
    }
    Map<String,Object> withdrawInput() {
        Map<String,Object> input=new HashMap<>();input.put("type","bank");input.put("network","BANK");input.put("currency","USD");input.put("address","OWNED-FAKE-ACCOUNT");input.put("amount","100");input.put("requestId","owned-withdraw-checkpoint");return input;
    }
    void bankCard(){UserBankCard card=new UserBankCard();card.setUserId(user);card.setCurrency("USD");card.setBankName("Owned synthetic bank");card.setRecipientName("Owned synthetic recipient");card.setRecipientAccount("OWNED-FAKE-ACCOUNT");bankCards.saveAndFlush(card);}
    WithdrawController withdrawal(String fault,boolean simulation) {
        FiatCurrencyService fiat=mock(FiatCurrencyService.class);when(fiat.currency("USD")).thenReturn("USD");when(fiat.rate("USD")).thenReturn(BigDecimal.ONE);when(fiat.toUsd(new BigDecimal("100"),BigDecimal.ONE)).thenReturn(new BigDecimal("100"));
        WithdrawController target=new WithdrawController(withdrawals,assets,mock(UserDigitalAddressRepository.class),bankCards,fiat){@Override protected void checkpoint(String stage){if(stage.equals(fault))throw new IllegalStateException("injected "+stage);}};
        ReflectionTestUtils.setField(target,"users",users);ReflectionTestUtils.setField(target,"em",em);ReflectionTestUtils.setField(target,"tenantPolicy",mock(TenantPolicyService.class));ReflectionTestUtils.setField(target,"audit",audit());
        if(simulation){KycIdentityService identity=mock(KycIdentityService.class);when(identity.simulationExempt()).thenReturn(true);ReflectionTestUtils.setField(target,"identityService",identity);}return target;
    }
    @Test void submissionFailuresRollbackFreezeAndUniqueRequestRecord() {
        bankCard();
        for(boolean simulation:new boolean[]{false,true})for(String fault:simulation?Arrays.asList("freeze-account","simulation-account","withdrawal-order","withdrawal-audit"):Arrays.asList("freeze-account","withdrawal-order","withdrawal-audit")) {
            assertEquals(400,proxy(withdrawal(fault,simulation)).submitWithdraw(new UsernamePasswordAuthenticationToken(user.toString(),null),withdrawInput()).getStatusCodeValue());
            equalsMoney("1000",balance("FUND"));equalsMoney("0",frozen());assertFalse(withdrawals.findByTenantIdAndUserIdAndRequestKey(1L,user,"owned-withdraw-checkpoint").isPresent());assertEquals(0,audits("WITHDRAW_SUBMIT"));
        }
        WithdrawController unavailable=withdrawal(null,false);ReflectionTestUtils.setField(unavailable,"audit",failedAudit());assertEquals(400,proxy(unavailable).submitWithdraw(new UsernamePasswordAuthenticationToken(user.toString(),null),withdrawInput()).getStatusCodeValue());
        equalsMoney("1000",balance("FUND"));equalsMoney("0",frozen());assertFalse(withdrawals.findByTenantIdAndUserIdAndRequestKey(1L,user,"owned-withdraw-checkpoint").isPresent());assertEquals(0,audits("WITHDRAW_SUBMIT"));
        WithdrawController service=proxy(withdrawal(null,false));assertEquals(200,service.submitWithdraw(new UsernamePasswordAuthenticationToken(user.toString(),null),withdrawInput()).getStatusCodeValue());assertEquals(200,service.submitWithdraw(new UsernamePasswordAuthenticationToken(user.toString(),null),withdrawInput()).getStatusCodeValue());equalsMoney("900",balance("FUND"));equalsMoney("100",frozen());assertEquals(1,audits("WITHDRAW_SUBMIT"));
    }
    WithdrawReviewController withdrawReview(String fault) {
        WithdrawReviewController target=new WithdrawReviewController(withdrawals,assets,mock(UserBankCardRepository.class),users,null,null,null){@Override protected void checkpoint(String stage){if(stage.equals(fault))throw new IllegalStateException("injected "+stage);}};
        ReflectionTestUtils.setField(target,"em",em);ReflectionTestUtils.setField(target,"controlAudit",audit());return proxy(target);
    }
    @Test void rejectionAndCompletionFailuresRollbackFundsAndWithdrawalState() {
        new TransactionTemplate(manager).executeWithoutResult(status->{AssetAccount fund=assets.lockByUserId(user).stream().filter(a->"FUND".equals(a.getCoin())).findFirst().orElseThrow();fund.setFrozen(new BigDecimal("100"));assets.save(fund);});
        WithdrawRecord record=new WithdrawRecord();record.setUserId(user);record.setType("bank");record.setNetwork("BANK");record.setAddress("OWNED-FAKE-ACCOUNT");record.setAmount(new BigDecimal("100"));record.setFee(BigDecimal.ZERO);record.setActualAmount(new BigDecimal("100"));Long id=withdrawals.saveAndFlush(record).getId();
        WithdrawReviewController.RejectRequest input=new WithdrawReviewController.RejectRequest();input.setRemark("Owned synthetic rejection");
        for(String fault:Arrays.asList("approval-order","approval-audit")){
            assertEquals(400,withdrawReview(fault).approveWithdraw(id,null,null).getStatusCodeValue());assertEquals("PENDING",withdrawals.findByTenantIdAndId(1L,id).orElseThrow().getStatus());equalsMoney("1000",balance("FUND"));equalsMoney("100",frozen());assertEquals(0,audits("WITHDRAW_APPROVED"));
        }
        for(String fault:Arrays.asList("rejection-account","rejection-order","rejection-audit")) {
            assertEquals(400,withdrawReview(fault).rejectWithdraw(id,input,null).getStatusCodeValue());
            equalsMoney("1000",balance("FUND"));equalsMoney("100",frozen());assertEquals("PENDING",withdrawals.findByTenantIdAndId(1L,id).orElseThrow().getStatus());assertEquals(0,audits("WITHDRAW_REJECTED"));
        }
        new TransactionTemplate(manager).executeWithoutResult(status->{WithdrawRecord current=withdrawals.lockById(id).orElseThrow();current.setStatus("APPROVED");withdrawals.save(current);});
        for(String fault:Arrays.asList("completion-account","completion-order","completion-audit")) {
            assertEquals(400,withdrawReview(fault).completeWithdraw(id,null).getStatusCodeValue());
            equalsMoney("1000",balance("FUND"));equalsMoney("100",frozen());assertEquals("APPROVED",withdrawals.findByTenantIdAndId(1L,id).orElseThrow().getStatus());assertEquals(0,audits("WITHDRAW_COMPLETED"));
        }
        assertEquals(200,withdrawReview(null).completeWithdraw(id,null).getStatusCodeValue());assertEquals(400,withdrawReview(null).completeWithdraw(id,null).getStatusCodeValue());equalsMoney("1000",balance("FUND"));equalsMoney("0",frozen());assertEquals(1,audits("WITHDRAW_COMPLETED"));
    }
    @Test void staleManagedUserAssetAndLoanAreRefreshedBeforeHibernateLockUpgrade() {
        Long id=signedLoan().getId();
        new TransactionTemplate(manager).executeWithoutResult(status->{
            UserAccount oldUser=users.findByTenantIdAndId(1L,user).orElseThrow();AssetAccount oldFund=assets.findByTenantIdAndUserIdAndCoin(1L,user,"FUND").orElseThrow();LoanRecord oldLoan=loans.findByTenantIdAndId(1L,id).orElseThrow();assertEquals("SIGNED",oldLoan.getStatus());equalsMoney("1000",oldFund.getAvailable());
            TransactionTemplate peer=new TransactionTemplate(manager);peer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            peer.executeWithoutResult(other->{UserAccount owner=users.findByTenantIdAndId(1L,user).orElseThrow();owner.setNickname("OWNED-STALENESS-PEER");users.save(owner);AssetAccount fund=assets.findByTenantIdAndUserIdAndCoin(1L,user,"FUND").orElseThrow();fund.setAvailable(new BigDecimal("900"));assets.save(fund);LoanRecord loan=loans.findByTenantIdAndId(1L,id).orElseThrow();loan.setStatus("APPROVED");loan.setApprovedAt(LocalDateTime.now());loans.save(loan);});
            repayment(null).earlyRepayment(id,user);assertEquals("OWNED-STALENESS-PEER",oldUser.getNickname());equalsMoney("800",oldFund.getAvailable());assertEquals("COMPLETED",oldLoan.getStatus());
        });
        equalsMoney("800",balance("FUND"));assertEquals("COMPLETED",loans.findByTenantIdAndId(1L,id).orElseThrow().getStatus());assertEquals(1,audits("LOAN_REPAY"));
    }
}
