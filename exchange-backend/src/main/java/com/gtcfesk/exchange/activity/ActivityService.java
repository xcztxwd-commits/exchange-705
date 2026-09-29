package com.gtcfesk.exchange.activity;
import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class ActivityService {
 private final ActivityCampaignRepository campaigns;
 private final ActivityDeliveryRepository deliveries;
 private final UserAccountRepository users;
 private final TrialFunds funds;
 private final ObjectMapper mapper;
 @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;
 public ActivityCampaign get(Long id){return campaigns.findById(id).orElseThrow(()->new BusinessException("活动不存在"));}
 private ActivityCampaign locked(Long id){return campaigns.lock(id).filter(c->!c.isDeleted()).orElseThrow(()->new BusinessException("活动不存在"));}
 @Transactional public ActivityCampaign save(Long id,ActivityCampaign input){
  ActivityCampaign c=id==null?new ActivityCampaign():locked(id);
  if(input.getName()==null||input.getName().trim().isEmpty()||input.getName().length()>120)throw new BusinessException("请填写活动名称（最多120字）");
  if(!Arrays.asList("DRAFT","ACTIVE","PAUSED","CLOSED").contains(input.getStatus()))throw new BusinessException("活动状态无效");
  if(!Arrays.asList("GIFT","CONFETTI","NONE").contains(input.getAnimation()))throw new BusinessException("动画类型无效");
  BigDecimal amount=input.getAmount(),budget=input.getBudget();
  if(amount==null||amount.signum()<=0||amount.compareTo(new BigDecimal("1000000"))>0||amount.stripTrailingZeros().scale()>2)throw new BusinessException("体验金金额须为0.01至1000000，最多两位小数");
  if(budget==null||budget.compareTo(amount)<0||budget.compareTo(c.getGranted())<0||budget.compareTo(new BigDecimal("1000000000"))>0||budget.stripTrailingZeros().scale()>2)throw new BusinessException("预算无效或低于已发放金额");
  if(input.getRecentLoginDays()<0||input.getRecentLoginDays()>365||input.getMaxClaims()<Math.max(1,c.getClaimCount())||input.getMaxClaims()>1000000)throw new BusinessException("登录天数或领取名额无效");
  if(input.getStartsAt()!=null&&input.getEndsAt()!=null&&!input.getEndsAt().isAfter(input.getStartsAt()))throw new BusinessException("结束时间必须晚于开始时间");
  if(id!=null&&deliveries.countByCampaignId(id)>0&&(c.getAmount().compareTo(amount)!=0||c.isTemplate()!=input.isTemplate()))throw new BusinessException("已发送活动的金额、资格和模板属性不可修改，请复制为新活动");
  validateTranslations(input);
  c.setLayoutJson(ActivityDesignValidator.validate(input.getLayoutJson(),input.getDefaultLocale(),mapper));
  c.setAutoSendEnabled(!input.isTemplate() && input.isAutoSendEnabled());c.setRepeatUnread(input.isRepeatUnread());
  c.setName(input.getName().trim());c.setStatus(input.isTemplate()?"DRAFT":input.getStatus());c.setTemplate(input.isTemplate());c.setAutoPopup(input.isAutoPopup());c.setAnimation(input.getAnimation());c.setDefaultLocale(input.getDefaultLocale());c.setTranslations(input.getTranslations());
  c.setAmount(amount);c.setBudget(budget);c.setMaxClaims(input.getMaxClaims());c.setRecentLoginDays(input.getRecentLoginDays());c.setStartsAt(input.getStartsAt());c.setEndsAt(input.getEndsAt());return campaigns.save(c);
 }
 @Transactional public ActivityCampaign saveAutoSend(Long id,ActivityCampaign input){
  ActivityCampaign c=locked(id);
  if(c.isTemplate())throw new BusinessException("模板不可自动发送，请先保存为活动");
  if(input.getRecentLoginDays()<0||input.getRecentLoginDays()>365||input.getMaxClaims()<Math.max(1,c.getClaimCount())||input.getMaxClaims()>1000000)throw new BusinessException("登录天数或领取名额无效");
  BigDecimal budget=input.getBudget();
  if(budget==null||budget.compareTo(c.getAmount())<0||budget.compareTo(c.getGranted())<0||budget.compareTo(new BigDecimal("1000000000"))>0||budget.stripTrailingZeros().scale()>2)throw new BusinessException("预算无效或低于已发放金额");
  if(input.getStartsAt()!=null&&input.getEndsAt()!=null&&!input.getEndsAt().isAfter(input.getStartsAt()))throw new BusinessException("结束时间必须晚于开始时间");
  c.setAutoSendEnabled(input.isAutoSendEnabled());c.setRecentLoginDays(input.getRecentLoginDays());c.setStartsAt(input.getStartsAt());c.setEndsAt(input.getEndsAt());c.setMaxClaims(input.getMaxClaims());c.setBudget(budget);
  return campaigns.save(c);
 }
 @Transactional public void delete(Long id){
  ActivityCampaign c=locked(id);
  if(c.isTemplate())throw new BusinessException("模板仅支持编辑或另存为活动");
  // Keep deliveries and financial ledgers for audit; deleted campaigns cannot grant credit.
  c.setDeleted(true);c.setAutoSendEnabled(false);c.setStatus("CLOSED");campaigns.save(c);
 }
 public List<Map<String,Object>> searchRecipients(String query){
  String text=query==null?"":query.trim();if(text.isEmpty())return Collections.emptyList();
  if(text.length()>128)throw new BusinessException("搜索内容不能超过128字符");
  long id=-1;try{if(text.matches("[0-9]+"))id=Long.parseLong(text);}catch(NumberFormatException ignored){}
  String email="%"+text.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
  List<Object[]> rows=entityManager.createQuery("select u.id,u.email from UserAccount u where (u.id=:id or lower(u.email) like :email escape '!') order by u.id",Object[].class)
   .setParameter("id",id).setParameter("email",email).setMaxResults(20).getResultList();
  List<Map<String,Object>> result=new ArrayList<>();for(Object[] row:rows){Map<String,Object> user=new LinkedHashMap<>();user.put("id",row[0]);user.put("email",row[1]);result.add(user);}return result;
 }
 @Transactional public void autoSendDue(){autoDeliver(null);}
 private void autoDeliver(Long user){
  // History stays readable when the tenant stops accepting new business.
  UserAccount visitor=user==null?null:users.findById(user).orElseThrow(()->new BusinessException("用户不存在"));
  List<ActivityCampaign> candidates=campaigns.findAll((root,q,cb)->cb.and(cb.isTrue(root.get("autoSendEnabled")),cb.isFalse(root.get("deleted")),cb.isFalse(root.get("template")),cb.equal(root.get("status"),"ACTIVE")),Sort.by("id"));
  for(ActivityCampaign candidate:candidates){
   if(!candidate.active())continue;
   if(visitor!=null&&(!eligible(candidate,visitor)||deliveries.findByCampaignIdAndUserId(candidate.getId(),user).isPresent()))continue;
   ActivityCampaign c=campaigns.lock(candidate.getId()).orElse(null);
   if(c!=null)entityManager.refresh(c,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
   if(c==null||!c.isAutoSendEnabled()||!c.active()||c.getClaimCount()>=c.getMaxClaims()||c.getGranted().add(c.getAmount()).compareTo(c.getBudget())>0)continue;
   List<UserAccount> targets;
   if(visitor!=null)targets=Collections.singletonList(visitor);
   else {
    // Bounded batches resume via NOT EXISTS on the next tick; no in-memory cursor to lose.
    javax.persistence.TypedQuery<UserAccount> query=entityManager.createQuery("select u from UserAccount u where u.status='normal'"+(c.getRecentLoginDays()>0?" and u.lastLoginAt>=:since":"")+" and not exists (select d.id from ActivityDelivery d where d.campaignId=:campaign and d.userId=u.id) order by u.id",UserAccount.class).setParameter("campaign",c.getId()).setMaxResults(500);
    if(c.getRecentLoginDays()>0)query.setParameter("since",LocalDateTime.now().minusDays(c.getRecentLoginDays()));
    targets=query.getResultList();
   }
   for(UserAccount u:targets){
    if(!eligible(c,u))continue;
    // Current locking read also handles concurrent manual sends under MySQL REPEATABLE READ.
    if(!entityManager.createQuery("select d from ActivityDelivery d where d.campaignId=:campaign and d.userId=:user",ActivityDelivery.class).setParameter("campaign",c.getId()).setParameter("user",u.getId()).setLockMode(javax.persistence.LockModeType.PESSIMISTIC_WRITE).getResultList().isEmpty())continue;
    ActivityDelivery d=new ActivityDelivery();d.setCampaignId(c.getId());d.setUserId(u.getId());d.setSentBy("AUTO");deliveries.save(d);
   }
   deliveries.flush();
  }
 }
 private void validateTranslations(ActivityCampaign c){
  try{
   if(c.getTranslations()==null||c.getTranslations().length()>100000)throw new IllegalArgumentException();
   JsonNode n=mapper.readTree(c.getTranslations());if(!n.isObject()||n.size()>30||c.getDefaultLocale()==null||!n.has(c.getDefaultLocale()))throw new IllegalArgumentException();
   Iterator<Map.Entry<String,JsonNode>> fields=n.fields();while(fields.hasNext()){
    Map.Entry<String,JsonNode> f=fields.next();JsonNode v=f.getValue();
    if(!f.getKey().matches("[a-z]{2}(-[A-Za-z]{2,4})?")||!v.isObject())throw new IllegalArgumentException();
    for(String key:Arrays.asList("title","body","terms"))if(!v.path(key).isTextual()||v.path(key).asText().trim().isEmpty()||v.path(key).asText().length()>(key.equals("title")?160:10000))throw new IllegalArgumentException();
    for(String key:Arrays.asList("open","close","claim","success"))if(v.has(key)&&(!v.get(key).isTextual()||v.get(key).asText().length()>200))throw new IllegalArgumentException();
   }
  }catch(Exception e){throw new BusinessException("请填写有效多语言文案（默认语言、标题、正文、规则必填）");}
 }
 @Transactional public Map<String,Object> send(Long id,List<Long> ids,String admin){
  ActivityCampaign c=locked(id);if(c.isTemplate()||!"ACTIVE".equals(c.getStatus())||(c.getEndsAt()!=null&&!LocalDateTime.now().isBefore(c.getEndsAt())))throw new BusinessException("请启用未过期活动后发送");
  if(ids==null||ids.isEmpty()||ids.size()>1000||ids.stream().anyMatch(x->x==null||x<=0))throw new BusinessException("每批请选择1至1000个用户ID");
  Set<Long> unique=new LinkedHashSet<>(ids);List<UserAccount> recipients=users.findAllById(unique);
  if(recipients.size()!=unique.size())throw new BusinessException("存在无效用户ID，本批次未发送");
  int sent=0,duplicate=0,skipped=0;
  for(UserAccount u:recipients){
   if(!eligible(c,u)){skipped++;continue;}
   if(deliveries.findByCampaignIdAndUserId(id,u.getId()).isPresent()){duplicate++;continue;}
   ActivityDelivery d=new ActivityDelivery();d.setCampaignId(id);d.setUserId(u.getId());d.setSentBy(admin);deliveries.save(d);sent++;
  }
  Map<String,Object> r=new LinkedHashMap<>();r.put("sent",sent);r.put("duplicates",duplicate);r.put("ineligible",skipped);return r;
 }
 private boolean eligible(ActivityCampaign c,UserAccount u){return "normal".equals(u.getStatus())&&(c.getRecentLoginDays()==0||(u.getLastLoginAt()!=null&&!u.getLastLoginAt().isBefore(LocalDateTime.now().minusDays(c.getRecentLoginDays()))));}
 @Transactional public Page<Map<String,Object>> inbox(Long user,int page){if(page<=0)autoDeliver(user);return deliveries.findByUserIdOrderByIdDesc(user,PageRequest.of(Math.max(0,page),20)).map(d->{
  ActivityCampaign c=get(d.getCampaignId());Map<String,Object> r=new LinkedHashMap<>();r.put("delivery",d);r.put("campaign",publicCampaign(c));r.put("active",c.active());return r;
 });}
 public Map<String,Object> message(Long user,Long id){ActivityDelivery d=owned(user,id);ActivityCampaign c=get(d.getCampaignId());Map<String,Object> m=new LinkedHashMap<>();m.put("delivery",d);m.put("campaign",publicCampaign(c));m.put("active",c.active());return m;}
 @Transactional public ActivityDelivery event(Long user,Long id,String type){
  users.lockById(user).orElseThrow(()->new BusinessException("用户不存在"));
  ActivityDelivery d=owned(user,id);LocalDateTime now=LocalDateTime.now();
  if(!Arrays.asList("RECEIVED","OPENED","CLOSED").contains(type))throw new BusinessException("事件类型无效");
  if(d.getReceivedAt()==null)d.setReceivedAt(now);
  if("OPENED".equals(type)){if(d.getOpenedAt()==null)d.setOpenedAt(now);d.setOpenCount(d.getOpenCount()+1);}
  if("CLOSED".equals(type)){d.setClosedAt(now);d.setCloseCount(d.getCloseCount()+1);}
  return deliveries.save(d);
 }
 private Map<String,Object> publicCampaign(ActivityCampaign c){Map<String,Object> m=new LinkedHashMap<>();m.put("id",c.getId());m.put("layoutJson",c.getLayoutJson());m.put("amount",c.getAmount());m.put("translations",c.getTranslations());m.put("defaultLocale",c.getDefaultLocale());m.put("startsAt",c.getStartsAt());m.put("endsAt",c.getEndsAt());m.put("status",c.getStatus());m.put("autoPopup",c.isAutoPopup());m.put("repeatUnread",c.isRepeatUnread());m.put("animation",c.getAnimation());m.put("recentLoginDays",c.getRecentLoginDays());m.put("hasQuota",c.getClaimCount()<c.getMaxClaims()&&c.getGranted().add(c.getAmount()).compareTo(c.getBudget())<=0);return m;}
 private ActivityDelivery owned(Long user,Long id){return deliveries.findById(id).filter(d->d.getUserId().equals(user)).orElseThrow(()->new BusinessException("消息不存在"));}
 @Transactional public TrialAccount claim(Long user,Long id){
  // Always campaign -> user -> assets lock order; send/edit only lock campaign.
  ActivityDelivery before=owned(user,id);ActivityCampaign c=locked(before.getCampaignId());funds.lock(user);
  ActivityDelivery d=owned(user,id);
  if(entityManager!=null)entityManager.refresh(d,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
  if(d.getClaimedAt()!=null)return funds.snapshot(user);
  if(!c.active())throw new BusinessException("活动未开始、已暂停或已结束");
  UserAccount u=users.findById(user).orElseThrow(()->new BusinessException("用户不存在"));
  if(!eligible(c,u))throw new BusinessException("不符合活动领取条件");
  if(c.getClaimCount()>=c.getMaxClaims()||c.getGranted().add(c.getAmount()).compareTo(c.getBudget())>0)throw new BusinessException("活动名额或预算已用完");
  LocalDateTime now=LocalDateTime.now();d.setClaimedAt(now);if(d.getReceivedAt()==null)d.setReceivedAt(now);if(d.getOpenedAt()==null)d.setOpenedAt(now);deliveries.save(d);
  c.setClaimCount(c.getClaimCount()+1);c.setGranted(c.getGranted().add(c.getAmount()));campaigns.save(c);
  return funds.grant(user,c.getAmount(),d.getId());
 }
 public Map<String,Object> stats(Long id){ActivityCampaign c=get(id);Map<String,Object> m=new LinkedHashMap<>();m.put("sent",deliveries.countByCampaignId(id));m.put("received",deliveries.countByCampaignIdAndReceivedAtIsNotNull(id));m.put("opened",deliveries.countByCampaignIdAndOpenedAtIsNotNull(id));m.put("closed",deliveries.countByCampaignIdAndClosedAtIsNotNull(id));m.put("closedWithoutOpening",deliveries.countByCampaignIdAndClosedAtIsNotNullAndOpenedAtIsNull(id));m.put("claimed",c.getClaimCount());m.put("granted",c.getGranted());return m;}
}
