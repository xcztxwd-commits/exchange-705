package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.market.MarketIconController;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MarketIconBoundaryTest {
 @Test void bundledIconsLoadOnAdminHostWithoutOpeningOtherMarketOrTenantRoutes() throws Exception {
  TenantRepository tenants=mock(TenantRepository.class);
  TenantHostService hosts=new TenantHostService(tenants,"example.test","https://admin.example.test","https://control.example.test","");
  TenantRequestFilter boundary=new TenantRequestFilter(hosts);
  MockMvc mvc=MockMvcBuilders.standaloneSetup(new MarketIconController()).addFilters(boundary).build();
  for(String path:new String[]{"/api/market/icons/crypto/BTC.svg","/api/market/icons/crypto/ETH.svg","/api/market/icons/forex/AUD-USD.svg","/api/market/icons/forex/USD-CAD.svg"}) {
   assertTrue(TenantRequestFilter.backendSharedPath(path));
   mvc.perform(get(path).param("v","2").header("Host","admin.example.test"))
    .andExpect(status().isOk()).andExpect(content().contentType("image/svg+xml"));
   assertNull(TenantContext.currentTenantId());
  }
  verifyNoInteractions(tenants);
  mvc.perform(get("/api/market/icons/crypto/BTC.svg").header("Host","admin.example.test").header("Origin","https://evil.example.test"))
   .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_BOUNDARY_REJECTED"));
  mvc.perform(get("/api/market/icons/crypto/BTC.svg").header("Host","unknown.example.test"))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/market/symbols").header("Host","admin.example.test"))
   .andExpect(status().isForbidden());
  for(String path:new String[]{"/api/market/icons/crypto/btc.svg","/api/market/icons/crypto/BTC.png","/api/market/icons/crypto/../secret.svg","/api/market/icons/crypto/BTC.svg/extra","/api/market/icons/private/BTC.svg","/api/market/symbols","/api/user/assets"})
   assertFalse(TenantRequestFilter.backendSharedPath(path));
  assertNull(TenantContext.currentTenantId());
 }
 @Test void publicAdminIconExceptionNeverOpensTenantEntryBusinessRoutes() throws Exception {
  TenantRepository tenants=mock(TenantRepository.class);
  TenantDomainVerification domains=mock(TenantDomainVerification.class);
  TenantHostService hosts=new TenantHostService(tenants,"forex-exchange.cc","https://admin.forex-exchange.cc","https://control.forex-exchange.cc","");
  TenantRequestFilter boundary=new TenantRequestFilter(hosts);
  org.springframework.test.util.ReflectionTestUtils.setField(boundary,"domains",domains);
  MockMvc mvc=MockMvcBuilders.standaloneSetup(new MarketIconController()).addFilters(boundary).build();
  mvc.perform(get("/api/market/icons/crypto/BTC.svg").header("Host","entry.forex-exchange.net").header("Accept","text/html"))
   .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"))
   .andExpect(header().doesNotExist("Location")).andExpect(jsonPath("$.code").value("TENANT_BOUNDARY_REJECTED"));
  verifyNoInteractions(tenants,domains);
  assertNull(TenantContext.currentTenantId());
 }
}
