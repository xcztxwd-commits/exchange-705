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
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;
 private final ActivityCampaignRepository campaigns;
 private final ActivityDeliveryRepository deliveries;
 private final UserAccountRepository users;
 private final TrialFunds funds;
 private final ObjectMapper mapper;
 @org.springframework.beans.factory.annotation.Autowired private TrialGrantRepository grants;
 @org.springframework.beans.factory.annotation.Autowired private ActivitySelectionRepository selections;
 @org.springframework.beans.factory.annotation.Autowired private ActivitySelectionMemberRepository members;
 @org.springframework.beans.factory.annotation.Autowired private ActivitySendReceiptRepository sendReceipts;
 private static final Set<String> POSITIONS=new HashSet<>(Arrays.asList("ANONYMOUS_HOME","AUTH_HOME","AUTH_TRADE","AUTH_PROFILE","SUPPORT"));
 private static final Set<String> TRIGGERS=new HashSet<>(Arrays.asList("LOGIN","PAGE_HOME","PAGE_TRADE","PAGE_PROFILE","PAGE_SUPPORT","API_CONTRACT_ORDER","API_OPTION_ORDER"));
 @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;
 public ActivityCampaign get(Long id){return campaigns.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).orElseThrow(()->new BusinessException("活动不存在"));}
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
  if(id!=null&&deliveries.countByTenantIdAndCampaignId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id)>0&&(c.getAmount().compareTo(amount)!=0||c.isTemplate()!=input.isTemplate()))throw new BusinessException("已发送活动的金额、资格和模板属性不可修改，请复制为新活动");
  validateTranslations(input);validateSettings(input);
  c.setLayoutJson(ActivityDesignValidator.validate(input.getLayoutJson(),input.getDefaultLocale(),mapper));
  c.setAutoSendEnabled(!input.isTemplate() && input.isAutoSendEnabled());c.setRepeatUnread(input.isRepeatUnread());c.setAllowRepeatSend(input.isAllowRepeatSend());c.setAllowRepeatClaim(input.isAllowRepeatClaim());c.setClaimValidityDays(input.getClaimValidityDays());c.setPositions(new ArrayList<>(input.getPositions()));c.setTriggerConditions(new ArrayList<>(input.getTriggerConditions()));
  c.setName(input.getName().trim());c.setStatus(input.isTemplate()?"DRAFT":input.getStatus());c.setTemplate(input.isTemplate());c.setAutoPopup(input.isAutoPopup());c.setAnimation(input.getAnimation());c.setDefaultLocale(input.getDefaultLocale());c.setTranslations(input.getTranslations());
  c.setAmount(amount);c.setBudget(budget);c.setMaxClaims(input.getMaxClaims());c.setRecentLoginDays(input.getRecentLoginDays());c.setStartsAt(input.getStartsAt());c.setEndsAt(input.getEndsAt());return campaigns.save(c);
 }
 @Transactional public ActivityCampaign saveAutoSend(Long id,ActivityCampaign input){
  ActivityCampaign c=locked(id);
  if(c.isTemplate())throw new BusinessException("模板不可自动发送，请先保存为活动");
  if(input.getRecentLoginDays()<0||input.getRecentLoginDays()>365||input.getMaxClaims()<Math.max(1,c.getClaimCount())||input.getMaxClaims()>1000000)throw new BusinessException("登录天数或领取名额无效");
  if(!Arrays.asList("DRAFT","ACTIVE","PAUSED","CLOSED").contains(input.getStatus()))throw new BusinessException("活动状态无效");
  if(!Arrays.asList("GIFT","CONFETTI","NONE").contains(input.getAnimation()))throw new BusinessException("动画类型无效");
  BigDecimal amount=input.getAmount(),budget=input.getBudget();
  if(amount==null||amount.signum()<=0||amount.compareTo(new BigDecimal("1000000"))>0||amount.stripTrailingZeros().scale()>2)throw new BusinessException("体验金金额须为0.01至1000000，最多两位小数");
  if(budget==null||budget.compareTo(amount)<0||budget.compareTo(c.getGranted())<0||budget.compareTo(new BigDecimal("1000000000"))>0||budget.stripTrailingZeros().scale()>2)throw new BusinessException("预算无效或低于已发放金额");
  if(deliveries.countByTenantIdAndCampaignId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),id)>0&&c.getAmount().compareTo(amount)!=0)throw new BusinessException("已发送活动的金额不可修改，请复制为新活动");
  if(input.getStartsAt()!=null&&input.getEndsAt()!=null&&!input.getEndsAt().isAfter(input.getStartsAt()))throw new BusinessException("结束时间必须晚于开始时间");
  validateSettings(input);
  c.setStatus(input.getStatus());c.setAmount(amount);c.setAutoPopup(input.isAutoPopup());c.setRepeatUnread(input.isRepeatUnread());c.setAnimation(input.getAnimation());
  c.setAutoSendEnabled(input.isAutoSendEnabled());c.setRecentLoginDays(input.getRecentLoginDays());c.setStartsAt(input.getStartsAt());c.setEndsAt(input.getEndsAt());c.setMaxClaims(input.getMaxClaims());c.setBudget(budget);c.setAllowRepeatClaim(input.isAllowRepeatClaim());c.setAllowRepeatSend(input.isAllowRepeatSend());c.setClaimValidityDays(input.getClaimValidityDays());c.setPositions(new ArrayList<>(input.getPositions()));c.setTriggerConditions(new ArrayList<>(input.getTriggerConditions()));
  return campaigns.save(c);
 }
 @Transactional public ActivityCampaign saveContent(Long id,ActivityContentPatch input){
  ActivityCampaign c=locked(id);
  if(input==null||input.getRowVersion()==null||input.getRowVersion()!=c.getRowVersion())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"活动已被更新，请刷新");
  if(input.getName()==null||input.getName().trim().isEmpty()||input.getName().length()>120)throw new BusinessException("活动名称无效");
  ActivityCampaign copy=new ActivityCampaign();copy.setDefaultLocale(input.getDefaultLocale());copy.setTranslations(input.getTranslations());validateTranslations(copy);
  c.setName(input.getName().trim());c.setDefaultLocale(input.getDefaultLocale());c.setTranslations(input.getTranslations());
  c.setLayoutJson(ActivityDesignValidator.validate(input.getLayoutJson(),input.getDefaultLocale(),mapper));
  return campaigns.saveAndFlush(c);
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
  List<Object[]> rows=entityManager.createQuery("select u.id,u.email from UserAccount u where u.tenantId=:tenant and (u.id=:id or lower(u.email) like :email escape '!') order by u.id",Object[].class)
   .setParameter("tenant",com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()).setParameter("id",id).setParameter("email",email).setMaxResults(20).getResultList();
  List<Map<String,Object>> result=new ArrayList<>();for(Object[] row:rows){Map<String,Object> user=new LinkedHashMap<>();user.put("id",row[0]);user.put("email",row[1]);result.add(user);}return result;
 }
 @Deprecated public void autoSendDue(){/* Time-based broadcasting removed. */}
 private void validateSettings(ActivityCampaign input){
  if(input.getClaimValidityDays()!=null&&(input.getClaimValidityDays()<1||input.getClaimValidityDays()>3650))throw new BusinessException("领取时效须为1至3650天");
  if(input.getPositions()==null||input.getPositions().isEmpty()||input.getPositions().size()>5||!POSITIONS.containsAll(input.getPositions())||new HashSet<>(input.getPositions()).size()!=input.getPositions().size())throw new BusinessException("展示位置无效");
  if(input.getTriggerConditions()==null||input.getTriggerConditions().size()>TRIGGERS.size()||!TRIGGERS.containsAll(input.getTriggerConditions())||new HashSet<>(input.getTriggerConditions()).size()!=input.getTriggerConditions().size())throw new BusinessException("触发条件无效");
 }
 /** Server-issued successful event only. Reads, heartbeats and arbitrary URLs never reach this method. */
 @Transactional public int trigger(Long user,String event,String position){
  if(!TRIGGERS.contains(event)||!POSITIONS.contains(position)||"ANONYMOUS_HOME".equals(position))throw new BusinessException("触发事件无效");
  try{tenantPolicy.requireNewBusiness("activity");}catch(org.springframework.security.access.AccessDeniedException denied){return 0;}
  Long tenant=com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
  UserAccount visitor=users.findByTenantIdAndId(tenant,user).orElseThrow(()->new BusinessException("用户不存在"));
  int sent=0;
  for(ActivityCampaign candidate:campaigns.findAllByTenantId(tenant,(root,q,cb)->cb.and(cb.isTrue(root.get("autoSendEnabled")),cb.isFalse(root.get("deleted")),cb.isFalse(root.get("template")),cb.equal(root.get("status"),"ACTIVE")),Sort.by("id"))){
   if(!candidate.active()||!candidate.getPositions().contains(position)||!candidate.getTriggerConditions().contains(event)||!eligible(candidate,visitor))continue;
   ActivityCampaign campaign=locked(candidate.getId());
   if(!campaign.isAutoSendEnabled()||!campaign.active()||!campaign.getPositions().contains(position)||!campaign.getTriggerConditions().contains(event)||campaign.getClaimCount()>=campaign.getMaxClaims()||campaign.getGranted().add(campaign.getAmount()).compareTo(campaign.getBudget())>0)continue;
   ActivityDelivery prior=deliveries.findByTenantIdAndCampaignIdAndUserId(tenant,campaign.getId(),user).orElse(null);
   if(prior!=null){if(!campaign.isAllowRepeatSend())continue;prior.setSentAt(LocalDateTime.now());prior.setReceivedAt(null);prior.setOpenedAt(null);prior.setClosedAt(null);prior.setSentBy("AUTO:"+event);deliveries.save(prior);}
   else {ActivityDelivery next=new ActivityDelivery();next.setCampaignId(campaign.getId());next.setUserId(user);next.setSentBy("AUTO:"+event);deliveries.saveAndFlush(next);}
   sent++;
  }
  return sent;
 }
 public Page<Map<String,Object>> publicActivities(String position,int page){
  if(!"ANONYMOUS_HOME".equals(position)||page<0||page>100000)throw new BusinessException("公开位置无效");
  List<Map<String,Object>> visible=new ArrayList<>();
  for(ActivityCampaign c:campaigns.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),Sort.by(Sort.Direction.DESC,"id"))){
   if(!c.active()||!c.getPositions().contains(position)||c.getClaimCount()>=c.getMaxClaims()||c.getGranted().add(c.getAmount()).compareTo(c.getBudget())>0)continue;
   Map<String,Object> view=publicCampaign(c);view.remove("recentLoginDays");visible.add(view);
  }
  int from=(int)Math.min((long)visible.size(),(long)page*20L),to=Math.min(visible.size(),from+20);
  return new PageImpl<>(visible.subList(from,to),PageRequest.of(page,20),visible.size());
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
 @Transactional public Map<String,Object> send(Long id,List<Long> ids,String admin){return send(id,ids,admin,null);}
 @Transactional public Map<String,Object> send(Long id,List<Long> ids,String admin,String operationId){
  ActivityCampaign c=locked(id);requireSendable(c);
  if(ids==null||ids.isEmpty()||ids.size()>1000||ids.stream().anyMatch(x->x==null||x<=0))throw new BusinessException("每批请选择1至1000个用户ID");
  String hash=hash(new TreeSet<>(ids).toString());
  if(operationId!=null){requireOperation(operationId);ActivitySendReceipt prior=sendReceipts.findByTenantIdAndCampaignIdAndOperationId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),id,operationId).orElse(null);
   if(prior!=null){if(!prior.getPayloadHash().equals(hash))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"幂等键已用于其他收件人");
    try{return mapper.readValue(prior.getResultJson(),Map.class);}catch(Exception e){throw new IllegalStateException("发送收据无效",e);}
   }
  }
  Set<Long> unique=new LinkedHashSet<>(ids);List<UserAccount> recipients=users.findAllByTenantIdAndIdIn(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),unique);
  if(recipients.size()!=unique.size())throw new BusinessException("存在无效用户ID，本批次未发送");
  int sent=0,duplicate=0,skipped=0;
  for(UserAccount u:recipients){
   if(!eligible(c,u)){skipped++;continue;}
   ActivityDelivery existing=deliveries.findByTenantIdAndCampaignIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),id,u.getId()).orElse(null);
   if(existing!=null){
    if(!c.isAllowRepeatSend()){duplicate++;continue;}
    existing.setSentAt(LocalDateTime.now());existing.setSentBy(admin);existing.setReceivedAt(null);existing.setOpenedAt(null);existing.setClosedAt(null);
    deliveries.save(existing);sent++;continue;
   }
   ActivityDelivery d=new ActivityDelivery();d.setCampaignId(id);d.setUserId(u.getId());d.setSentBy(admin);deliveries.save(d);sent++;
  }
  Map<String,Object> result=new LinkedHashMap<>();result.put("sent",sent);result.put("duplicates",duplicate);result.put("ineligible",skipped);
  if(operationId!=null){ActivitySendReceipt receipt=new ActivitySendReceipt();receipt.setCampaignId(id);receipt.setOperationId(operationId);receipt.setPayloadHash(hash);
   try{receipt.setResultJson(mapper.writeValueAsString(result));}catch(Exception e){throw new IllegalStateException("发送收据序列化失败",e);}sendReceipts.saveAndFlush(receipt);}
  return result;
 }
 private void requireSendable(ActivityCampaign c){if(c.isTemplate()||!"ACTIVE".equals(c.getStatus())||!c.active())throw new BusinessException("请启用未过期活动后发送");}
 private void requireOperation(String key){if(key==null||!key.matches("[A-Za-z0-9_-]{16,64}"))throw new BusinessException("操作幂等键无效");}
 private String hash(String text){try{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 private boolean eligible(ActivityCampaign c,UserAccount u){return "normal".equals(u.getStatus())&&(c.getRecentLoginDays()==0||(u.getLastLoginAt()!=null&&!u.getLastLoginAt().isBefore(LocalDateTime.now().minusDays(c.getRecentLoginDays()))));}
 private org.springframework.data.jpa.domain.Specification<UserAccount> recipientSpec(RecipientFilter f){
  if(f==null)throw new BusinessException("筛选条件无效");
  if(f.getPage()<0||f.getPage()>100000||f.getSize()<1||f.getSize()>100)throw new BusinessException("分页参数无效");
  if(f.getQuery()!=null&&f.getQuery().length()>128)throw new BusinessException("搜索内容过长");
  if(f.getCreatedFrom()!=null&&f.getCreatedTo()!=null&&f.getCreatedFrom().isAfter(f.getCreatedTo())||
     f.getLastLoginFrom()!=null&&f.getLastLoginTo()!=null&&f.getLastLoginFrom().isAfter(f.getLastLoginTo()))throw new BusinessException("时间范围无效");
  List<Long> ids=f.getClaimedCampaignIds()==null?Collections.emptyList():f.getClaimedCampaignIds();
  if(ids.size()>20||ids.stream().anyMatch(id->id==null||id<=0))throw new BusinessException("活动筛选项无效");
  if(!ids.isEmpty()){
   if(!Arrays.asList("INCLUDE","EXCLUDE").contains(f.getClaimedMode()))throw new BusinessException("活动包含方式无效");
   for(Long id:ids)if(!campaigns.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),id).isPresent())throw new BusinessException("活动筛选项无效");
  }
  return (root,q,cb)->{
   List<javax.persistence.criteria.Predicate> clauses=new ArrayList<>();clauses.add(cb.equal(root.get("status"),"normal"));
   String text=f.getQuery()==null?"":f.getQuery().trim();if(!text.isEmpty()){
    long id=-1;try{id=Long.parseLong(text);}catch(NumberFormatException ignored){}
    String pattern="%"+text.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    clauses.add(cb.or(cb.equal(root.get("id"),id),cb.like(cb.lower(root.get("email")),pattern,'!')));
   }
   if(f.getCreatedFrom()!=null)clauses.add(cb.greaterThanOrEqualTo(root.get("createdAt"),f.getCreatedFrom()));
   if(f.getCreatedTo()!=null)clauses.add(cb.lessThanOrEqualTo(root.get("createdAt"),f.getCreatedTo()));
   if(f.getLastLoginFrom()!=null)clauses.add(cb.greaterThanOrEqualTo(root.get("lastLoginAt"),f.getLastLoginFrom()));
   if(f.getLastLoginTo()!=null)clauses.add(cb.lessThanOrEqualTo(root.get("lastLoginAt"),f.getLastLoginTo()));
   if(!ids.isEmpty()){
    javax.persistence.criteria.Subquery<Long> claimed=q.subquery(Long.class);javax.persistence.criteria.Root<ActivityDelivery> delivery=claimed.from(ActivityDelivery.class);
    claimed.select(delivery.get("userId")).where(cb.equal(delivery.get("tenantId"),com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()),delivery.get("campaignId").in(ids),cb.isNotNull(delivery.get("claimedAt")),cb.equal(delivery.get("userId"),root.get("id")));
    clauses.add("EXCLUDE".equals(f.getClaimedMode())?cb.not(cb.exists(claimed)):cb.exists(claimed));
   }
   return cb.and(clauses.toArray(new javax.persistence.criteria.Predicate[0]));
  };
 }
 public Page<Map<String,Object>> searchRecipients(RecipientFilter f){
  org.springframework.data.jpa.domain.Specification<UserAccount> spec=recipientSpec(f);
  return users.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),spec,PageRequest.of(f.getPage(),f.getSize(),Sort.by("id")))
   .map(u->{Map<String,Object> row=new LinkedHashMap<>();row.put("id",u.getId());row.put("email",u.getEmail());row.put("createdAt",u.getCreatedAt());row.put("lastLoginAt",u.getLastLoginAt());return row;});
 }
 private ActivitySelection selected(Long campaign,Long selectionId){
  ActivitySelection view=selections.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),selectionId).filter(s->s.getCampaignId().equals(campaign)).orElseThrow(()->new BusinessException("选择集合不存在"));
  return selections.lock(campaign,view.getOperationId()).orElseThrow(()->new BusinessException("选择集合不存在"));
 }
 private Map<String,Object> selectionStatus(ActivitySelection s){Map<String,Object> r=new LinkedHashMap<>();r.put("selectionId",s.getId());r.put("selectionOperationId",s.getOperationId());r.put("sendOperationId",s.getSendOperationId());r.put("selected",s.getSelected());r.put("sent",s.getSent());r.put("duplicates",s.getDuplicates());r.put("ineligible",s.getIneligible());r.put("remaining",s.getSelected()-s.getSent()-s.getDuplicates()-s.getIneligible());r.put("done",s.isDone());return r;}
 @Transactional public Map<String,Object> selectAll(Long campaignId,String operationId,RecipientFilter filter){return selectAll(campaignId,operationId,filter,null);}
 @Transactional public Map<String,Object> selectAll(Long campaignId,String operationId,RecipientFilter filter,List<Long> userIds){
  requireOperation(operationId);ActivityCampaign c=locked(campaignId);requireSendable(c);
  if(filter!=null)recipientSpec(filter);
  if(userIds!=null&&(userIds.size()>1000||userIds.stream().anyMatch(id->id==null||id<=0)))throw new BusinessException("每次最多选择1000个有效用户");
  Set<Long> distinct=userIds==null?Collections.emptySet():new TreeSet<>(userIds);
  if(filter==null&&distinct.isEmpty())throw new BusinessException("请选择用户或筛选条件");
  String filterHash=hash((filter==null?"<explicit>":filter.stableKey())+"|"+distinct);
  ActivitySelection existing=selections.findByTenantIdAndCampaignIdAndOperationId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),campaignId,operationId).orElse(null);
  if(existing!=null){if(!existing.getFilterHash().equals(filterHash))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"选择幂等键已用于其他筛选");return selectionStatus(existing);}
  ActivitySelection s=new ActivitySelection();s.setCampaignId(campaignId);s.setOperationId(operationId);s.setFilterHash(filterHash);s=selections.saveAndFlush(s);
  appendSelection(campaignId,s.getId(),new ArrayList<>(distinct),filter);return selectionStatus(s);
 }
 private long appendFiltered(ActivitySelection s,RecipientFilter filter){
  org.springframework.data.jpa.domain.Specification<UserAccount> spec=recipientSpec(filter);long added=0;int page=0;
  // ponytail: one selection transaction is a stable DB snapshot; split by server-side cursor if very large campaigns need it.
  while(true){Page<UserAccount> usersPage=users.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),spec,PageRequest.of(page++,500,Sort.by("id")));
   for(UserAccount u:usersPage){if(addMember(s,u.getId()))added++;}
   members.flush();if(!usersPage.hasNext())break;
  }
  s.setSelected(s.getSelected()+added);selections.save(s);return added;
 }
 private boolean addMember(ActivitySelection s,Long user){
  if(members.findByTenantIdAndSelectionIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),s.getId(),user).isPresent())return false;
  ActivitySelectionMember row=new ActivitySelectionMember();row.setSelectionId(s.getId());row.setUserId(user);members.save(row);return true;
 }
 @Transactional public Map<String,Object> appendSelection(Long campaignId,Long selectionId,List<Long> userIds,RecipientFilter filter){
  locked(campaignId);ActivitySelection s=selected(campaignId,selectionId);
  if(s.getSendOperationId()!=null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"发送已开始，不能编辑集合");
  if((userIds==null||userIds.isEmpty())&&filter==null)throw new BusinessException("请提供用户或筛选条件");
  long added=0;if(userIds!=null&&!userIds.isEmpty()){
   if(userIds.size()>1000||userIds.stream().anyMatch(id->id==null||id<=0))throw new BusinessException("每次最多追加1000个用户");
   Set<Long> distinct=new LinkedHashSet<>(userIds);List<UserAccount> found=users.findAllByTenantIdAndIdIn(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),distinct);
   if(found.size()!=distinct.size())throw new BusinessException("存在无效用户ID");
   for(Long id:distinct)if(addMember(s,id))added++;
  }
  s.setSelected(s.getSelected()+added);if(filter!=null)added+=appendFiltered(s,filter);selections.save(s);
  Map<String,Object> result=selectionStatus(s);result.put("added",added);return result;
 }
 @Transactional public Map<String,Object> removeSelection(Long campaignId,Long selectionId,List<Long> userIds){
  locked(campaignId);ActivitySelection s=selected(campaignId,selectionId);
  if(s.getSendOperationId()!=null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"发送已开始，不能编辑集合");
  if(userIds==null||userIds.isEmpty()||userIds.size()>1000)throw new BusinessException("请选择要移除的用户");
  long removed=0;for(Long id:new LinkedHashSet<>(userIds)){
   ActivitySelectionMember row=members.findByTenantIdAndSelectionIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),s.getId(),id).orElse(null);
   if(row!=null){members.delete(row);removed++;}
  }
  s.setSelected(s.getSelected()-removed);selections.save(s);Map<String,Object> result=selectionStatus(s);result.put("removed",removed);return result;
 }
 public Page<Map<String,Object>> selectionMembers(Long campaignId,Long selectionId,int page,int size){
  if(page<0||page>100000||size<1||size>100)throw new BusinessException("分页参数无效");
  ActivitySelection s=selections.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),selectionId).filter(v->v.getCampaignId().equals(campaignId)).orElseThrow(()->new BusinessException("选择集合不存在"));
  return members.findByTenantIdAndSelectionIdOrderByIdAsc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),s.getId(),PageRequest.of(page,size))
   .map(member->{Map<String,Object> row=new LinkedHashMap<>();row.put("userId",member.getUserId());row.put("email",users.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),member.getUserId()).map(UserAccount::getEmail).orElse(null));return row;});
 }
 @Transactional public Map<String,Object> sendSelection(Long campaignId,Long selectionId,String sendOperationId,String admin){
  requireOperation(sendOperationId);ActivityCampaign c=locked(campaignId);requireSendable(c);ActivitySelection s=selected(campaignId,selectionId);
  if(s.getSendOperationId()!=null&&!s.getSendOperationId().equals(sendOperationId))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"集合已绑定其他发送操作");
  if(s.getSendOperationId()==null)s.setSendOperationId(sendOperationId);
  if(s.isDone())return selectionStatus(s);
  List<ActivitySelectionMember> batch=members.findByTenantIdAndSelectionIdAndIdGreaterThanOrderByIdAsc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),selectionId,s.getCursorId(),PageRequest.of(0,500));
  if(!batch.isEmpty()){
   List<Long> ids=new ArrayList<>();for(ActivitySelectionMember row:batch)ids.add(row.getUserId());
   Map<String,Object> result=send(campaignId,ids,admin);
   s.setSent(s.getSent()+((Number)result.get("sent")).longValue());s.setDuplicates(s.getDuplicates()+((Number)result.get("duplicates")).longValue());
   s.setIneligible(s.getIneligible()+((Number)result.get("ineligible")).longValue());s.setCursorId(batch.get(batch.size()-1).getId());
  }
  if(batch.size()<500)s.setDone(true);selections.saveAndFlush(s);return selectionStatus(s);
 }
 @Transactional public Page<Map<String,Object>> inbox(Long user,int page){return deliveries.findByTenantIdAndUserIdOrderByIdDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), user,PageRequest.of(Math.max(0,page),20)).map(d->{
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
 private Map<String,Object> publicCampaign(ActivityCampaign c){Map<String,Object> m=new LinkedHashMap<>();m.put("id",c.getId());m.put("layoutJson",c.getLayoutJson());m.put("amount",c.getAmount());m.put("translations",c.getTranslations());m.put("defaultLocale",c.getDefaultLocale());m.put("startsAt",c.getStartsAt());m.put("endsAt",c.getEndsAt());m.put("status",c.getStatus());m.put("autoPopup",c.isAutoPopup());m.put("repeatUnread",c.isRepeatUnread());m.put("animation",c.getAnimation());m.put("recentLoginDays",c.getRecentLoginDays());m.put("claimValidityDays",c.getClaimValidityDays());m.put("allowRepeatClaim",c.isAllowRepeatClaim());m.put("positions",c.getPositions());m.put("hasQuota",c.getClaimCount()<c.getMaxClaims()&&c.getGranted().add(c.getAmount()).compareTo(c.getBudget())<=0);return m;}
 private ActivityDelivery owned(Long user,Long id){return deliveries.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).filter(d->d.getUserId().equals(user)).orElseThrow(()->new BusinessException("消息不存在"));}
 @Transactional public TrialAccount claim(Long user,Long id){return claim(user,id,null);}
 @Transactional public TrialAccount claim(Long user,Long id,String requestKey){
  ActivityDelivery before=owned(user,id);funds.lock(user);
  if(requestKey!=null&&!requestKey.matches("[A-Za-z0-9_-]{16,64}"))throw new BusinessException("幂等键无效");
  if(requestKey!=null){TrialGrant prior=grants.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),user,requestKey).orElse(null);
   if(prior!=null){if(!Objects.equals(prior.getCampaignId(),before.getCampaignId()))throw new BusinessException("幂等键已用于其他活动");return funds.snapshot(user);}
  }
  tenantPolicy.requireNewBusiness("activity");ActivityCampaign c=locked(before.getCampaignId());
  ActivityDelivery d=owned(user,id);if(entityManager!=null)entityManager.refresh(d,javax.persistence.LockModeType.PESSIMISTIC_WRITE);
  if(d.getClaimedAt()!=null&&(!c.isAllowRepeatClaim()||requestKey==null))return funds.snapshot(user);
  if(!c.active())throw new BusinessException("活动未开始、已暂停或已结束");
  UserAccount u=users.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),user).orElseThrow(()->new BusinessException("用户不存在"));
  if(!eligible(c,u))throw new BusinessException("不符合活动领取条件");
  if(c.getClaimCount()>=c.getMaxClaims()||c.getGranted().add(c.getAmount()).compareTo(c.getBudget())>0)throw new BusinessException("活动名额或预算已用完");
  LocalDateTime claimedAt=LocalDateTime.now();if(d.getClaimedAt()==null)d.setClaimedAt(claimedAt);
  if(d.getReceivedAt()==null)d.setReceivedAt(claimedAt);if(d.getOpenedAt()==null)d.setOpenedAt(claimedAt);deliveries.save(d);
  c.setClaimCount(c.getClaimCount()+1);c.setGranted(c.getGranted().add(c.getAmount()));campaigns.save(c);
  return funds.grant(user,c.getAmount(),c.getId(),d.getId(),requestKey==null?"delivery-"+d.getId():requestKey,c.getClaimValidityDays());
 }
 @Transactional public TrialAccount claimPublic(Long user,Long campaignId,String requestKey){
  if(requestKey==null||!requestKey.matches("[A-Za-z0-9_-]{16,64}"))throw new BusinessException("幂等键无效");
  funds.lock(user);TrialGrant prior=grants.findByTenantIdAndUserIdAndRequestKey(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),user,requestKey).orElse(null);
  if(prior!=null){if(!Objects.equals(prior.getCampaignId(),campaignId))throw new BusinessException("幂等键已用于其他活动");return funds.snapshot(user);}
  ActivityCampaign c=locked(campaignId);
  if(!c.active()||!c.getPositions().contains("ANONYMOUS_HOME"))throw new BusinessException("公开活动不存在或已结束");
  ActivityDelivery d=deliveries.findByTenantIdAndCampaignIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),campaignId,user).orElse(null);
  if(d==null){d=new ActivityDelivery();d.setCampaignId(campaignId);d.setUserId(user);d.setSentBy("PUBLIC");d=deliveries.saveAndFlush(d);}
  return claim(user,d.getId(),requestKey);
 }
 public Map<String,Object> stats(Long id){ActivityCampaign c=get(id);Map<String,Object> m=new LinkedHashMap<>();m.put("sent",deliveries.countByTenantIdAndCampaignId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id));m.put("received",deliveries.countByTenantIdAndCampaignIdAndReceivedAtIsNotNull(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id));m.put("opened",deliveries.countByTenantIdAndCampaignIdAndOpenedAtIsNotNull(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id));m.put("closed",deliveries.countByTenantIdAndCampaignIdAndClosedAtIsNotNull(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id));m.put("closedWithoutOpening",deliveries.countByTenantIdAndCampaignIdAndClosedAtIsNotNullAndOpenedAtIsNull(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id));m.put("claimed",c.getClaimCount());m.put("granted",c.getGranted());return m;}
}
