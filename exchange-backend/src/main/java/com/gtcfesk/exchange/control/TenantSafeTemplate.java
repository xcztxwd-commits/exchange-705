package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.math.BigDecimal;
/** Versioned code-owned defaults. Never clone live products, payment destinations or credentials. */
@Service @RequiredArgsConstructor
public class TenantSafeTemplate {
 public static final String VERSION="safe-v1",PRODUCT_NAME="SAFE-V1-DISABLED";
 private final AdminRoleRepository roles;private final SystemConfigRepository configs;
 private final TradingSymbolRepository symbols;private final FinancialProductRepository products;private final TenantRepository tenants;
 @Transactional public void initialize(Long tenant){try(TenantContext.Scope scope=TenantContext.open(tenant)){
  // The tenant row serializes retries, including product names which have no unique constraint.
  Tenant registration=tenants.lock(tenant).orElseThrow(()->new IllegalArgumentException("租户不存在"));
  if(!VERSION.equals(registration.getTemplateVersion()))throw new IllegalArgumentException("不支持的租户模板版本");
  for(String code:new String[]{"admin","ops"})if(!roles.existsByTenantIdAndRoleCode(tenant,code)){
   AdminRole role=new AdminRole();role.setRoleCode(code);role.setRoleName("admin".equals(code)?"管理员":"运营");role.setDescription("新租户安全模板：初始无业务菜单授权");role.setStatus("active");role.setIsSuper(false);roles.saveAndFlush(role);
  }
  for(String[] entry:new String[][]{{"site.name","未配置平台"},{"system.timezone","UTC"},{"support.settings","{\"mode\":\"off\",\"inboxEnabled\":false}"}})if(!configs.findByTenantIdAndConfigKey(tenant,entry[0]).isPresent()){
   SystemConfig c=new SystemConfig();c.setConfigKey(entry[0]);c.setConfigValue(entry[1]);c.setDescription(VERSION+" 安全初始配置");configs.saveAndFlush(c);
  }
  if(!symbols.findByTenantIdAndSymbol(tenant,"BTCUSD").isPresent()){
   TradingSymbol symbol=new TradingSymbol();symbol.setSymbol("BTCUSD");symbol.setName("Bitcoin (disabled template)");symbol.setNameEn("Bitcoin (disabled template)");symbol.setBaseCurrency("BTC");symbol.setQuoteCurrency("USD");symbol.setCategory("Crypto");symbol.setMarketSource("YAHOO");symbol.setSourceCategory("Crypto");symbol.setIsEnabled(false);symbol.setIsHot(false);symbol.setCurrentPrice(BigDecimal.ZERO);symbol.setMinTradeAmount(new BigDecimal("0.01"));symbol.setControlEnabled(false);symbol.setLotSize(BigDecimal.ONE);symbol.setLeverage(BigDecimal.ONE);symbol.setMaxLeverage(BigDecimal.ONE);symbol.setFeeMultiplier(BigDecimal.ZERO);symbol.setQuantityUnitType("LOT");symbol.setMinOrderQuantity(new BigDecimal("0.01"));symbol.setQuantityStep(new BigDecimal("0.01"));symbols.saveAndFlush(symbol);
  }
  if(products.countByTenantId(tenant,(root,query,builder)->builder.equal(root.get("name"),PRODUCT_NAME))==0){
   FinancialProduct product=new FinancialProduct();product.setName(PRODUCT_NAME);product.setDescription("safe-v1 禁用示例，零收益；启用前须独立审核并设置真实业务参数。");product.setCurrency("USD");product.setEnabled(false);product.setDailyYieldRate(BigDecimal.ZERO);product.setRentalFee(BigDecimal.ZERO);product.setMinPurchase(new BigDecimal("100"));product.setMaxPurchase(new BigDecimal("1000"));product.setTermDays(7);product.setPenaltyRate(BigDecimal.ZERO);products.saveAndFlush(product);
  }
 }}
}
