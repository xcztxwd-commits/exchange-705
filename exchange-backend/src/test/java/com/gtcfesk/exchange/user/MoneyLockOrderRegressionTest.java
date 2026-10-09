package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.LoanReviewService;
import com.gtcfesk.exchange.admin.WithdrawReviewController;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.OrderRequest;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline path/order checks only. MySQL RR and physical rollback are separate integration gates. */
class MoneyLockOrderRegressionTest {
    TenantContext.Scope scope;
    UserAccountRepository users;
    AssetAccountRepository assets;
    UserAccount user;
    AssetAccount fund;
    @BeforeEach void start() {
        scope=TenantContext.open(1L);users=mock(UserAccountRepository.class);assets=mock(AssetAccountRepository.class);
        user=new UserAccount();user.setId(7L);when(users.lockById(7L)).thenReturn(Optional.of(user));
        fund=new AssetAccount();fund.setUserId(7L);fund.setCoin("FUND");fund.setAvailable(new BigDecimal("1000"));
        when(assets.lockByUserId(7L)).thenReturn(Collections.singletonList(fund));
    }
    @AfterEach void end(){scope.close();TenantContext.clear();SecurityContextHolder.clearContext();}
    LoanRecord loan(String status) {
        LoanRecord record=new LoanRecord();record.setId(9L);record.setUserId(7L);record.setStatus(status);
        record.setAmount(new BigDecimal("100"));record.setDailyRate(BigDecimal.ZERO);record.setFreeDays(7);record.setDays(7);
        record.setRealName("Owned fixture");record.setIdNumber("NOT-A-REAL-IDENTITY");record.setApprovedAt(LocalDateTime.now());return record;
    }
    LoanPersonalInfoService personal() {
        LoanPersonalInfoService personal=mock(LoanPersonalInfoService.class);LoanPersonalInfo info=new LoanPersonalInfo();
        info.setUserId(7L);info.setRealName("Owned fixture");info.setIdNumber("NOT-A-REAL-IDENTITY");
        when(personal.requireApprovedPersonalInfo(7L)).thenReturn(info);
        when(personal.requireApprovedPersonalInfoForFunds(7L)).thenReturn(info);return personal;
    }
    LoanService loans(LoanRecordRepository records) {
        LoanService service=new LoanService(records,mock(LoanSettingRepository.class),users,mock(KycRecordRepository.class),assets,personal());
        ReflectionTestUtils.setField(service,"audit",mock(ControlAuditService.class));
        ReflectionTestUtils.setField(service,"tenantPolicy",mock(TenantPolicyService.class));return service;
    }
    @Test void repaymentLocksUserAndOrderedAssetsBeforeCurrentOrderAndReplaysWithoutDebit() {
        LoanRecordRepository records=mock(LoanRecordRepository.class);LoanRecord record=loan("APPROVED");when(records.lockById(9L)).thenReturn(Optional.of(record));
        LoanService service=loans(records);service.earlyRepayment(9L,7L);
        InOrder ordered=inOrder(users,assets,records);ordered.verify(users).lockById(7L);ordered.verify(assets).lockByUserId(7L);ordered.verify(records).lockById(9L);
        assertEquals(new BigDecimal("900.0000000000000000"),fund.getAvailable().setScale(16));
        service.earlyRepayment(9L,7L);assertEquals(0,new BigDecimal("900").compareTo(fund.getAvailable()));verify(assets,times(1)).save(fund);
        verify(assets,never()).findByTenantIdAndUserIdAndCoin(anyLong(),anyLong(),anyString());
    }
    @Test void approvalUsesOwnerOnlyForRoutingAndRejectsChangedFinalOwnership() {
        LoanRecordRepository records=mock(LoanRecordRepository.class);LoanRecord preview=loan("SIGNED"),current=loan("SIGNED");current.setUserId(8L);
        when(records.findOwnerIdById(9L)).thenReturn(Optional.of(7L));when(records.findByTenantIdAndId(1L,9L)).thenReturn(Optional.of(preview));when(records.lockById(9L)).thenReturn(Optional.of(current));
        LoanReviewService review=new LoanReviewService(records,assets,users,personal());
        ReflectionTestUtils.setField(review,"audit",mock(ControlAuditService.class));ReflectionTestUtils.setField(review,"tenantPolicy",mock(TenantPolicyService.class));
        assertThrows(BusinessException.class,()->review.approveLoan(9L));
        InOrder ordered=inOrder(users,assets,records);ordered.verify(records).findOwnerIdById(9L);ordered.verify(users).lockById(7L);ordered.verify(assets).lockByUserId(7L);ordered.verify(records).lockById(9L);
        verify(assets,never()).save(any());verify(records,never()).save(any());assertEquals(new BigDecimal("1000"),fund.getAvailable());
    }
    @Test void signingCannotLockLoanBeforeUserAssets() {
        LoanRecordRepository records=mock(LoanRecordRepository.class);LoanRecord record=loan("PENDING");when(records.lockById(9L)).thenReturn(Optional.of(record));
        LoanService service=loans(records);service.signContract(9L,7L,"OWNED-NOT-A-REAL-SIGNATURE");
        InOrder ordered=inOrder(users,assets,records);ordered.verify(users).lockById(7L);ordered.verify(assets).lockByUserId(7L);ordered.verify(records).lockById(9L);
        assertEquals("SIGNED",record.getStatus());verify(assets,never()).save(any());
    }
    Map<String,Object> withdrawal() {
        Map<String,Object> request=new HashMap<>();request.put("type","bank");request.put("network","BANK");request.put("currency","JPY");request.put("amount","16000");
        request.put("address","OWNED-FAKE-ACCOUNT");request.put("requestId","money-lock-withdraw-replay");return request;
    }
    @Test void fixedWithdrawalReplaySurvivesUnavailableRatesAndReadsReceiptAfterAssets() {
        WithdrawRecordRepository records=mock(WithdrawRecordRepository.class);FiatCurrencyService fiat=mock(FiatCurrencyService.class);
        when(fiat.currency("JPY")).thenReturn("JPY");when(fiat.rate(anyString())).thenThrow(new BusinessException("expired"));
        Map<String,Object> request=withdrawal();WithdrawRecord receipt=new WithdrawRecord();receipt.setId(9L);receipt.setUserId(7L);
        receipt.setRequestHash(OrderRequest.hash("withdraw","bank","BANK",new BigDecimal("16000"),"JPY","OWNED-FAKE-ACCOUNT",null));
        when(records.findReplayId(1L,7L,"money-lock-withdraw-replay")).thenReturn(Optional.of(9L));
        when(records.findByTenantIdAndUserIdAndRequestKey(1L,7L,"money-lock-withdraw-replay")).thenReturn(Optional.of(receipt));
        WithdrawController controller=new WithdrawController(records,assets,mock(UserDigitalAddressRepository.class),mock(UserBankCardRepository.class),fiat, mock(com.gtcfesk.exchange.admin.SystemConfigService.class));
        ReflectionTestUtils.setField(controller,"users",users);ReflectionTestUtils.setField(controller,"tenantPolicy",mock(TenantPolicyService.class));
        assertEquals(200,controller.submitWithdraw(new UsernamePasswordAuthenticationToken("7",null),request).getStatusCodeValue());
        InOrder ordered=inOrder(users,assets,records);ordered.verify(users).lockById(7L);ordered.verify(assets).lockByUserId(7L);ordered.verify(records).findByTenantIdAndUserIdAndRequestKey(1L,7L,"money-lock-withdraw-replay");
        verify(fiat,never()).rate(anyString());verify(assets,never()).save(any());verify(records,never()).save(any());
    }
    @Test void withdrawalReviewLocksAssetsBeforeRecordAndDoesNotRevalueSavedAmount() {
        WithdrawRecordRepository records=mock(WithdrawRecordRepository.class);WithdrawRecord record=new WithdrawRecord();record.setId(9L);record.setUserId(7L);
        record.setStatus("PENDING");record.setAmount(new BigDecimal("100"));record.setFee(BigDecimal.ZERO);fund.setFrozen(new BigDecimal("100"));
        when(records.findOwnerIdById(9L)).thenReturn(Optional.of(7L));when(records.lockById(9L)).thenReturn(Optional.of(record));
        WithdrawReviewController controller=new WithdrawReviewController(records,assets,mock(UserBankCardRepository.class),users,null,null,null);ReflectionTestUtils.setField(controller,"controlAudit",mock(ControlAuditService.class));
        WithdrawReviewController.RejectRequest rejection=new WithdrawReviewController.RejectRequest();rejection.setRemark("Owned synthetic rejection");
        assertEquals(200,controller.rejectWithdraw(9L,rejection,null).getStatusCodeValue());
        InOrder ordered=inOrder(users,assets,records);ordered.verify(records).findOwnerIdById(9L);ordered.verify(users).lockById(7L);ordered.verify(assets).lockByUserId(7L);ordered.verify(records).lockById(9L);
        assertEquals("REJECTED",record.getStatus());assertEquals(new BigDecimal("1100"),fund.getAvailable());assertEquals(BigDecimal.ZERO,fund.getFrozen());
        assertEquals(400,controller.rejectWithdraw(9L,rejection,null).getStatusCodeValue());verify(assets,times(1)).save(fund);
    }
    @Test void transferUsesLockedRowsNotAnEarlierPlainSnapshot() {
        TransferRecordRepository records=mock(TransferRecordRepository.class);AssetAccount contract=new AssetAccount();contract.setCoin("CONTRACT");contract.setUserId(7L);
        when(assets.lockByUserId(7L)).thenReturn(Arrays.asList(contract,fund));
        TransferController controller=new TransferController(users,assets,records);ReflectionTestUtils.setField(controller,"audit",mock(ControlAuditService.class));TransferController.TransferRequest request=new TransferController.TransferRequest();
        request.setFromAccount("FUND");request.setToAccount("CONTRACT");request.setRequestId("money-lock-transfer");request.setAmount(BigDecimal.TEN);
        assertEquals(200,controller.transfer(new UsernamePasswordAuthenticationToken("7",null),request).getStatusCodeValue());
        InOrder ordered=inOrder(users,assets,records);ordered.verify(users).lockById(7L);ordered.verify(assets).lockByUserId(7L);ordered.verify(records).findByTenantIdAndUserIdAndRequestId(1L,7L,"money-lock-transfer");
        verify(assets,never()).findByTenantIdAndUserIdAndCoin(anyLong(),anyLong(),anyString());assertEquals(new BigDecimal("990"),fund.getAvailable());assertEquals(BigDecimal.TEN,contract.getAvailable());
    }
}
