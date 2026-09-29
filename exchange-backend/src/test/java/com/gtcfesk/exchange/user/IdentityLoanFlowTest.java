package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityLoanFlowTest {
    final KycRecordRepository kycs = mock(KycRecordRepository.class);
    final LoanPersonalInfoRepository infos = mock(LoanPersonalInfoRepository.class);
    final LoanPersonalInfoService service = new LoanPersonalInfoService(infos, new KycIdentityService(kycs));
    KycRecord identity;
    LoanPersonalInfo info;
    @BeforeEach void setup() {
        identity = new KycRecord(); identity.setUserId(1L); identity.setStatus("APPROVED");
        identity.setRealName("Approved Name"); identity.setIdNumber("ID-1");
        identity.setIdFrontImage("/uploads/front.png"); identity.setIdBackImage("/uploads/back.png");
        info = new LoanPersonalInfo(); info.setUserId(1L); info.setStatus("APPROVED");
        info.setRealName(identity.getRealName()); info.setIdNumber(identity.getIdNumber());
        info.setPhone("+819012345678"); info.setAddress("Test address"); info.setHandheldImage("/uploads/hand.png");
        when(kycs.findFirstByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(identity));
        when(infos.findByUserId(1L)).thenReturn(Optional.of(info));
        when(infos.save(any())).thenAnswer(call -> call.getArgument(0));
    }
    @Test void supplementNeedsApprovedBaseIdentity() {
        for (String status : Arrays.asList("PENDING", "REJECTED")) {
            identity.setStatus(status);
            assertThrows(BusinessException.class, () -> service.submitPersonalInfo(1L, info.getPhone(), info.getAddress(), info.getHandheldImage()));
        }
        when(kycs.findFirstByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.requireApprovedKyc(1L));
        verify(infos, never()).save(any());
    }
    @Test void supplementCopiesIdentityAndClearsPreviousReview() {
        info.setStatus("REJECTED"); info.setRealName("forged"); info.setIdNumber("forged");
        info.setReviewedBy(7L); info.setReviewedAt(java.time.LocalDateTime.now());
        service.submitPersonalInfo(1L, "+819012345678", "New address", "/uploads/new-hand.png");
        assertEquals(identity.getRealName(), info.getRealName());
        assertEquals(identity.getIdNumber(), info.getIdNumber());
        assertEquals(identity.getIdFrontImage(), info.getIdFrontImage());
        assertEquals("PENDING", info.getStatus()); assertNull(info.getReviewedBy()); assertNull(info.getReviewedAt());
    }
    @Test void pendingAndApprovedCannotBeResubmitted() {
        for (String status : Arrays.asList("PENDING", "APPROVED")) {
            info.setStatus(status);
            assertThrows(BusinessException.class, () -> service.submitPersonalInfo(1L, info.getPhone(), info.getAddress(), info.getHandheldImage()));
        }
    }
    @Test void placeholderOrMissingSupplementRejected() {
        info.setStatus("REJECTED");
        assertThrows(BusinessException.class, () -> service.submitPersonalInfo(1L, "00000000000", "Test", "/hand.png"));
        assertThrows(BusinessException.class, () -> service.submitPersonalInfo(1L, "+819012345678", "None", "/hand.png"));
        info.setHandheldImage(null);
        assertThrows(BusinessException.class, () -> service.submitPersonalInfo(1L, "+819012345678", "Test", null));
        verify(infos, never()).save(any());
    }
    @Test void legacyMismatchNeedsResubmissionNotAutomaticApproval() {
        info.setIdNumber("other-id");
        assertEquals("NEEDS_UPDATE", service.getPersonalInfoStatus(1L).get("status"));
        assertEquals(false, service.getPersonalInfoStatus(1L).get("verified"));
        assertThrows(BusinessException.class, () -> service.requireApprovedPersonalInfo(1L));
        service.submitPersonalInfo(1L, info.getPhone(), info.getAddress(), info.getHandheldImage());
        assertEquals("PENDING", info.getStatus());
    }
    @Test void statusIncludesPendingRejectedAndReason() {
        for (String status : Arrays.asList("PENDING", "REJECTED")) {
            info.setStatus(status); info.setReviewRemark("Please clarify address");
            Map<String, Object> result = service.getPersonalInfoStatus(1L);
            assertEquals(status, result.get("status")); assertEquals(false, result.get("verified"));
            assertEquals("Please clarify address", result.get("reviewRemark"));
            assertThrows(BusinessException.class, () -> service.requireApprovedPersonalInfo(1L));
        }
    }
    @Test void loanUsesReviewedDataAndCannotSkipReview() {
        LoanRecordRepository loans = mock(LoanRecordRepository.class);
        LoanSettingRepository settings = mock(LoanSettingRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        LoanService loan = new LoanService(loans, settings, users, kycs, mock(AssetAccountRepository.class), service);
        LoanSetting setting = new LoanSetting(); setting.setEnabled(true); setting.setDays(10); setting.setFreeDays(0); setting.setDailyRate(new BigDecimal("1"));
        when(settings.findById(3L)).thenReturn(Optional.of(setting));
        when(users.findById(1L)).thenReturn(Optional.of(new UserAccount()));
        when(loans.save(any())).thenAnswer(call -> call.getArgument(0));
        LoanRecord record = loan.createLoan(1L, BigDecimal.TEN, 3L);
        assertEquals(identity.getRealName(), record.getRealName()); assertEquals(identity.getIdNumber(), record.getIdNumber());
        assertEquals(info.getPhone(), record.getPhone()); assertEquals(info.getAddress(), record.getAddress());
        clearInvocations(loans);
        info.setStatus("PENDING");
        assertThrows(BusinessException.class, () -> loan.createLoan(1L, BigDecimal.TEN, 3L));
        verify(loans, never()).save(any());
    }
    @Test void reviewCannotApproveMismatchedLegacyIdentity() {
        info.setIdNumber("forged");
        assertThrows(BusinessException.class, () -> service.validateForReview(info));
    }
    @Test void oldSignedLoanCannotPayOutForDifferentIdentity() {
        LoanRecordRepository loans = mock(LoanRecordRepository.class);
        AssetAccountRepository assets = mock(AssetAccountRepository.class);
        LoanReviewService review = new LoanReviewService(loans, assets, mock(UserAccountRepository.class), service);
        LoanRecord record = new LoanRecord(); record.setUserId(1L); record.setStatus("SIGNED"); record.setRealName("forged"); record.setIdNumber("forged");
        when(loans.findById(8L)).thenReturn(Optional.of(record));
        assertThrows(BusinessException.class, () -> review.approveLoan(8L));
        verify(loans, never()).save(any()); verifyNoInteractions(assets);
    }
    @Test void approvedSupplementDoesNotReplaceBaseIdentity() {
        identity.setStatus("REJECTED");
        assertThrows(BusinessException.class, () -> service.requireApprovedPersonalInfo(1L));
        assertEquals(false, service.getPersonalInfoStatus(1L).get("verified"));
    }
    @Test void baseIdentityAloneDoesNotApproveLoanDetails() {
        when(infos.findByUserId(1L)).thenReturn(Optional.empty());
        Map<String, Object> result = service.getPersonalInfoStatus(1L);
        assertEquals(true, result.get("kycVerified")); assertEquals(false, result.get("verified"));
        assertEquals("NOT_SUBMITTED", result.get("status"));
        assertThrows(BusinessException.class, () -> service.requireApprovedPersonalInfo(1L));
    }
    @Test void multipartCannotReplaceApprovedIdentity() throws Exception {
        info.setStatus("REJECTED");
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new LoanPersonalInfoController(service, mock(FileUploadService.class))).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/loan/personal-info/submit")
                .principal(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("1", "unused"))
                .param("realName", "FORGED").param("idNumber", "FORGED-ID").param("idFrontImage", "/forged.png")
                .param("phone", "+819012345678").param("address", "Test address").param("handheldImage", "/hand.png"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.realName").value(identity.getRealName()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.idNumber").value(identity.getIdNumber()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.idFrontImage").value(identity.getIdFrontImage()));
    }
    @Test void approvedMatchingLoanPaysOutNormally() {
        LoanRecordRepository loans = mock(LoanRecordRepository.class);
        AssetAccountRepository assets = mock(AssetAccountRepository.class);
        LoanReviewService review = new LoanReviewService(loans, assets, mock(UserAccountRepository.class), service);
        LoanRecord record = new LoanRecord(); record.setUserId(1L); record.setStatus("SIGNED");
        record.setRealName(identity.getRealName()); record.setIdNumber(identity.getIdNumber()); record.setAmount(BigDecimal.TEN);
        AssetAccount fund = new AssetAccount(); fund.setAvailable(BigDecimal.ZERO);
        when(loans.findById(8L)).thenReturn(Optional.of(record));
        when(assets.findByUserIdAndCoin(1L, "FUND")).thenReturn(Optional.of(fund));
        review.approveLoan(8L);
        assertEquals("APPROVED", record.getStatus()); assertEquals(BigDecimal.TEN, fund.getAvailable());
    }

}
