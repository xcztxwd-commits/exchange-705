package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.KycRecord;
import com.gtcfesk.exchange.repository.KycRecordRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.user.KycIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KycOptionalReasonTest {
    private final KycRecordRepository records=mock(KycRecordRepository.class);
    private final KycIdentityService identities=mock(KycIdentityService.class);
    private KycReviewController controller;
    private KycRecord record;

    @BeforeEach void setup(){
        controller=new KycReviewController(records,mock(UserAccountRepository.class),mock(JwtUtil.class));
        ReflectionTestUtils.setField(controller,"identityService",identities);
        record=new KycRecord();record.setStatus("PENDING");when(identities.currentForReview(9L)).thenReturn(record);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   ","改"})
    void rejectWithoutReasonStillSavesReview(String reason){
        controller.rejectKyc(new UsernamePasswordAuthenticationToken("7",null),9L,reason==null?null:Collections.singletonMap("remark",reason));
        assertEquals("REJECTED",record.getStatus());assertEquals(reason,record.getReviewRemark());
        assertEquals(7L,record.getReviewedBy());assertNotNull(record.getReviewedAt());verify(records).save(record);
    }

    @Test void omittedReasonFieldIsAccepted(){
        controller.rejectKyc(null,9L,Collections.emptyMap());assertEquals("REJECTED",record.getStatus());verify(records).save(record);
    }

    @Test void reasonLengthAndAlreadyReviewedStateRemainValidated(){
        assertThrows(BusinessException.class,()->controller.rejectKyc(null,9L,Collections.singletonMap("remark","x".repeat(501))));
        assertEquals("PENDING",record.getStatus());verify(records,never()).save(any());
        record.setStatus("APPROVED");assertThrows(BusinessException.class,()->controller.rejectKyc(null,9L,Collections.emptyMap()));
        assertEquals("APPROVED",record.getStatus());verify(records,never()).save(any());
    }
}
