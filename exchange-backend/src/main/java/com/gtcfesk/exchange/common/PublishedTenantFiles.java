package com.gtcfesk.exchange.common;

import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.control.TenantRepository;
import com.gtcfesk.exchange.support.SupportSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import javax.persistence.*;
import java.util.*;

/** Only explicit public product references publish staff assets; never arbitrary uploaded files. */
@Service @RequiredArgsConstructor
public class PublishedTenantFiles {
 @PersistenceContext private EntityManager em;
 private final TenantRepository tenants;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.control.TenantHostService hosts;
 private final SupportSettings support;
 private final com.gtcfesk.exchange.admin.SystemConfigService configs;
 public boolean allows(String filename,boolean audio,boolean signedIn){
  Long tenant=TenantContext.requireTenantId();
  if(filename==null||!filename.matches(tenant+"/staff/(?:agent-)?-?[0-9]{1,19}/[a-zA-Z0-9_.-]+"))return false;
  String kind=audio?"audio":"images";List<String> urls=new ArrayList<>(Arrays.asList("/api/uploads/"+kind+"/"+filename,"/uploads/"+kind+"/"+filename));
  tenants.findById(tenant).ifPresent(t->{if(t.getFrontendHost()!=null){String origin=hosts==null?"https://"+t.getFrontendHost():hosts.frontendOrigin(t.getFrontendHost());urls.add(origin+urls.get(0));urls.add(origin+urls.get(1));}});
  if(audio){SupportSettings.Settings s=support.get();return "internal".equals(s.mode)&&urls.contains(s.userSound);}
  if(signedIn&&allowsShare(filename,true))return true;
  if(count("select count(p) from FinancialProduct p where p.tenantId=:tenant and p.enabled=true and p.imageUrl in :urls",tenant,urls)>0)return true;
  if(count("select count(s) from TradingSymbol s where s.tenantId=:tenant and s.isEnabled=true and (s.iconUrl in :urls or s.flagUrl in :urls)",tenant,urls)>0)return true;
  if(signedIn){for(String url:urls){String pattern="%\""+url.replace("!","!!").replace("%","!%").replace("_","!_")+"\"%";if(em.createQuery("select count(c) from ActivityCampaign c where c.tenantId=:tenant and c.deleted=false and c.template=false and c.status='ACTIVE' and c.layoutJson like :pattern escape '!'",Long.class).setParameter("tenant",tenant).setParameter("pattern",pattern).getSingleResult()>0)return true;}}
  return signedIn&&count("select count(d) from DepositSetting d where d.tenantId=:tenant and d.enabled=true and d.qrCode in :urls",tenant,urls)>0;
 }
 // Soft removal hides the library item, not its already-shared files used by draft copies.
 public boolean allowsMaterial(String filename){
  Long tenant=TenantContext.requireTenantId();
  if(filename==null||!filename.matches(tenant+"/staff/(?:agent-)?-?[0-9]{1,19}/[a-zA-Z0-9_.-]+"))return false;
  String url="/api/uploads/images/"+filename;
  String pattern="%\""+url.replace("!","!!").replace("%","!%").replace("_","!_")+"\"%";
  return em.createQuery("select count(m) from ActivityMaterial m where m.tenantId=:tenant and m.nodesJson like :pattern escape '!'",Long.class).setParameter("tenant",tenant).setParameter("pattern",pattern).getSingleResult()>0;
 }
 public boolean allowsShare(String filename,boolean publishedOnly){return configs.hasShareImage("/api/uploads/images/"+filename,publishedOnly);}
 public boolean allowsReview(String filename,Long user,java.util.Set<String> modules){
  Long tenant=TenantContext.requireTenantId();if(filename==null||!filename.matches(tenant+"/user/"+user+"/[a-zA-Z0-9_.-]+"))return false;
  List<String> urls=Arrays.asList("/api/uploads/images/"+filename,"/uploads/images/"+filename);
  String predicate=" where x.tenantId=:tenant and x.userId=:user and ";
  List<String> queries=new ArrayList<>();
  if(modules.contains("kyc_review"))queries.add("select count(x) from KycRecord x"+predicate+"(x.idFrontImage in :urls or x.idBackImage in :urls)");
  if(modules.contains("loan_personal_info_review"))queries.add("select count(x) from LoanPersonalInfo x"+predicate+"(x.idFrontImage in :urls or x.idBackImage in :urls or x.handheldImage in :urls)");
  if(modules.contains("loan_review"))queries.add("select count(x) from LoanRecord x"+predicate+"x.signatureImage in :urls");
  if(modules.contains("deposit_review")||modules.contains("deposit_orders"))queries.add("select count(x) from DepositRecord x"+predicate+"x.proofImage in :urls");
  for(String query:queries)if(em.createQuery(query,Long.class).setParameter("tenant",tenant).setParameter("user",user).setParameter("urls",urls).getSingleResult()>0)return true;
  return false;
 }
 private long count(String sql,Long tenant,List<String> urls){return em.createQuery(sql,Long.class).setParameter("tenant",tenant).setParameter("urls",urls).getSingleResult();}
}
