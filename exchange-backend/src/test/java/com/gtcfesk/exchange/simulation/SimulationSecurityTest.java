package com.gtcfesk.exchange.simulation;

import com.gtcfesk.exchange.demo.DemoModeBoundary;
import com.gtcfesk.exchange.user.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.common.KycRequiredException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimulationSecurityTest {
    SimulationEnvironment environment(boolean enabled) {
        SimulationEnvironment e = new SimulationEnvironment(mock(DataSource.class));
        ReflectionTestUtils.setField(e, "enabled", enabled); return e;
    }
    @Test void refusesRealDatabaseEvenWithDemoFlag() throws Exception {
        DataSource source=mock(DataSource.class); Connection c=mock(Connection.class);
        when(source.getConnection()).thenReturn(c); when(c.getCatalog()).thenReturn("1090");
        SimulationEnvironment e=new SimulationEnvironment(source); ReflectionTestUtils.setField(e,"enabled",true);
        assertThrows(IllegalStateException.class,e::verifyIsolation);
        when(c.getCatalog()).thenReturn("exchange_demo"); assertDoesNotThrow(e::verifyIsolation);
    }
    @Test void realProcessDoesNotRequireDemoDatabase() throws Exception {
        SimulationEnvironment e=environment(false); assertDoesNotThrow(e::verifyIsolation);
    }
    @Test void demoKycExemptionDoesNotForgeApprovalOrWriteIdentity() {
        KycRecordRepository records=mock(KycRecordRepository.class);
        when(records.findFirstByUserIdOrderByCreatedAtDesc(7L)).thenReturn(Optional.empty());
        KycIdentityService kyc=new KycIdentityService(records);
        ReflectionTestUtils.setField(kyc,"simulation",environment(true));
        assertTrue(kyc.canUseTradingFunds(7L)); assertFalse(kyc.isApproved(7L));
        assertEquals("SIMULATION_EXEMPT",kyc.requireApproved(7L).getStatus());
        verify(records,never()).save(any());
        LoanPersonalInfoService loans=new LoanPersonalInfoService(mock(LoanPersonalInfoRepository.class),kyc);
        assertEquals("SIMULATION_EXEMPT",loans.requireApprovedPersonalInfo(7L).getStatus());
        assertEquals(false,loans.getPersonalInfoStatus(7L).get("verified"));
        assertEquals(true,loans.getPersonalInfoStatus(7L).get("exempt"));
    }
    @Test void realKycStillRequired() {
        KycRecordRepository records=mock(KycRecordRepository.class);
        when(records.findFirstByUserIdOrderByCreatedAtDesc(7L)).thenReturn(Optional.empty());
        KycIdentityService kyc=new KycIdentityService(records);
        ReflectionTestUtils.setField(kyc,"simulation",environment(false));
        assertFalse(kyc.canUseTradingFunds(7L)); assertThrows(KycRequiredException.class,()->kyc.requireApproved(7L));
    }
    boolean boundary(boolean demo, String mode, String method, String path, int expected) throws Exception {
        DemoModeBoundary b=new DemoModeBoundary(); ReflectionTestUtils.setField(b,"simulation",environment(demo));
        MockHttpServletRequest r=new MockHttpServletRequest(method,path);
        if(mode!=null)r.addHeader("X-Account-Mode",mode);
        MockHttpServletResponse out=new MockHttpServletResponse(); boolean allowed=b.preHandle(r,out,new Object());
        assertEquals(expected,out.getStatus()); assertEquals(demo?"DEMO":"REAL",out.getHeader("X-Account-Environment"));return allowed;
    }
    @Test void allFinancialRoutesAreIsolatedBothWays() throws Exception {
        for(String path:Arrays.asList("/api/contract/create","/api/option/create","/api/transfer/submit","/api/withdraw/submit","/api/deposit/submit","/api/financial/purchase","/api/loan/create")) {
            assertFalse(boundary(false,"DEMO","POST",path,409));
            assertFalse(boundary(true,"REAL","POST",path,409));
            assertFalse(boundary(true,null,"POST",path,409));
            assertTrue(boundary(true,"DEMO","POST",path,200));
            assertTrue(boundary(false,"REAL","POST",path,200));
        }
    }
    @Test void demoCannotCreateLocalLoginOrAdminOrChangePassword() throws Exception {
        for(String path:Arrays.asList("/api/auth/login","/api/auth/register","/api/admin/auth/login","/api/user/changePassword"))
            assertFalse(boundary(true,"DEMO","POST",path,409));
    }
    @Test void publicMarketReadAllowedButNotMutation() throws Exception {
        assertTrue(boundary(true,null,"GET","/api/market/prices",200));
        assertFalse(boundary(true,null,"POST","/api/market/prices",409));
    }
    @Test void decodedServletPathCannotBypassLocalIdentityBoundary() throws Exception {
        SimulationIdentityBoundary filter=new SimulationIdentityBoundary(environment(true));
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/%61uth/register");request.setServletPath("/api/auth/register");
        MockHttpServletResponse response=new MockHttpServletResponse();
        filter.doFilter(request,response,(r,out)->fail("Identity action must not run"));assertEquals(409,response.getStatus());
    }

}
