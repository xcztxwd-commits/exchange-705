package com.gtcfesk.exchange.admin;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.*;
import javax.persistence.EntityManager;
import javax.persistence.criteria.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import static com.gtcfesk.exchange.user.DepositOrderService.error;

@RestController @RequestMapping("/api/admin/deposit/orders") @RequiredArgsConstructor
public class DepositOrderController {
 private final DepositOrderService service;
 private final DepositRecordRepository records;
 private final DepositCreditRecordRepository credits;
 private final UserAccountRepository users;
 private final AssetAccountRepository assets;
 private final BackendAccess access;
 private final EntityManager em;
 private final ObjectMapper mapper;
 private static final Sort SORT=Sort.by(Sort.Order.desc("createdAt"),Sort.Order.desc("id"));
 private void permit(String action){access.checkDeposit(action);}
 @GetMapping("/permissions") public Map<String,Object> permissions() {
  Map<String,Object> out=new LinkedHashMap<>();
  for(String a:Arrays.asList("view_deposit_orders","manual_deposit","export_deposit_orders")) {
   try{permit(a);out.put(a,true);}catch(org.springframework.security.access.AccessDeniedException e){out.put(a,false);}
  }
  for(String a:Arrays.asList("approve_deposit","reject_deposit")) {
   try{permit("view_deposit_orders");access.checkDepositReview(a);out.put(a,true);}catch(org.springframework.security.access.AccessDeniedException e){out.put(a,false);}
  }return out;
 }
 private Map<String,Object> dto(DepositRecord d) {
  Map<String,Object> out=mapper.convertValue(d,Map.class);
  out.remove("requestHash");out.remove("idempotencyKey");out.remove("rowVersion");
  out.put("amount",DepositOrderService.decimal(d.getAmount()));out.put("originalAmount",DepositOrderService.decimal(d.getOriginalAmount()));
  out.put("exchangeRate",DepositOrderService.decimal(d.getExchangeRate()));out.put("feeRate",DepositOrderService.decimal(d.getFeeRate()));out.put("feeAmount",DepositOrderService.decimal(d.getFeeAmount()));
  out.put("source",d.getSource()==null?"LEGACY_UNKNOWN":d.getSource());
  out.put("accountType",d.getAccountType()==null?"FUND":d.getAccountType());
  out.put("userRemark",users.findById(d.getUserId()).map(UserAccount::getRemark).orElse(null));return out;
 }
 private DepositRecord visible(Long id){DepositRecord d=records.findById(id).orElseThrow(()->error(404,"订单不存在"));
  try{access.checkUser(d.getUserId());}catch(org.springframework.security.access.AccessDeniedException e){throw error(404,"订单不存在");}return d;}
 @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable Long id){permit("view_deposit_orders");Map<String,Object> out=dto(visible(id));
  out.put("credit",credits.findByDepositRecordId(id).map(c->{Map<String,Object> m=mapper.convertValue(c,Map.class);m.put("amountUsd",DepositOrderService.decimal(c.getAmountUsd()));m.put("balanceBefore",DepositOrderService.decimal(c.getBalanceBefore()));m.put("balanceAfter",DepositOrderService.decimal(c.getBalanceAfter()));return m;}).orElse(null));return out;}
 @GetMapping("/recipient/{userId}") public Map<String,Object> recipient(@PathVariable Long userId){permit("manual_deposit");access.checkUser(userId);
  UserAccount u=users.findById(userId).orElseThrow(()->error(404,"客户不存在"));Map<String,Object> out=new LinkedHashMap<>();
  out.put("userId",userId);out.put("name",u.getEmail());out.put("remark",u.getRemark());Map<String,String> balances=new LinkedHashMap<>();
  for(AssetAccount a:assets.findByUserId(userId))balances.put(a.getCoin(),DepositOrderService.decimal(a.getAvailable()));out.put("balances",balances);return out;}
 @PostMapping("/manual") public Map<String,Object> manual(@RequestBody DepositOrderRequest input){DepositRecord d=service.manual(input);Map<String,Object> out=dto(d);out.put("success",true);return out;}
 @PostMapping("/{id}/approve") public Map<String,Object> approve(@PathVariable Long id,@RequestBody(required=false) Map<String,String> body){permit("view_deposit_orders");visible(id);return dto(service.review(id,true,body==null?null:body.get("remark")));}
 @PostMapping("/{id}/reject") public Map<String,Object> reject(@PathVariable Long id,@RequestBody Map<String,String> body){permit("view_deposit_orders");visible(id);return dto(service.review(id,false,body.get("remark")));}
 private int number(Map<String,String> p,String key,int def,int max){try{int n=Integer.parseInt(p.getOrDefault(key,""+def));if(n<1||n>max)throw error(400,"分页参数无效");return n;}catch(NumberFormatException e){throw error(400,"分页参数无效");}}
 @GetMapping("/list") public Map<String,Object> list(@RequestParam Map<String,String> p){permit("view_deposit_orders");int page=number(p,"page",1,1000000),size=number(p,"size",20,100);
  Page<DepositRecord> result=records.findAll(filter(p),PageRequest.of(page-1,size,SORT));Map<String,Object> out=new LinkedHashMap<>();
  out.put("list",result.getContent().stream().map(this::dto).collect(Collectors.toList()));out.put("total",result.getTotalElements());out.put("page",page);out.put("size",size);return out;}
 @GetMapping("/summary") public Map<String,Object> summary(@RequestParam Map<String,String> p){permit("view_deposit_orders");Specification<DepositRecord> f=filter(p);
  Map<String,Object> out=new LinkedHashMap<>();out.put("count",records.count(f));out.put("pending",records.count(f.and((r,q,b)->b.equal(r.get("status"),"PENDING"))));
  CriteriaBuilder b=em.getCriteriaBuilder();CriteriaQuery<Object[]> q=b.createQuery(Object[].class);Root<DepositRecord> r=q.from(DepositRecord.class);
  Expression<String> source=b.coalesce(r.<String>get("source"),"LEGACY_UNKNOWN");
  q.multiselect(source,r.get("manualPurpose"),b.sum(r.<BigDecimal>get("amount")),b.count(r));
  q.where(b.and(f.toPredicate(r,q,b),b.equal(r.get("status"),"COMPLETED")));q.groupBy(source,r.get("manualPurpose"));
  Map<String,String> totals=new LinkedHashMap<>();BigDecimal total=BigDecimal.ZERO;
  for(Object[] row:em.createQuery(q).getResultList()){BigDecimal amount=(BigDecimal)row[2];String key=(String)row[0];if(row[1]!=null)key+="_"+row[1];totals.put(key,amount.toPlainString());total=total.add(amount);}
  CriteriaQuery<Object[]> oq=b.createQuery(Object[].class);Root<DepositRecord> or=oq.from(DepositRecord.class);
  oq.multiselect(or.get("currency"),b.sum(or.<BigDecimal>get("originalAmount"))).where(b.and(f.toPredicate(or,oq,b),b.equal(or.get("status"),"COMPLETED"),b.isNotNull(or.get("originalAmount")))).groupBy(or.get("currency"));
  Map<String,String> originals=new LinkedHashMap<>();for(Object[] row:em.createQuery(oq).getResultList())originals.put(String.valueOf(row[0]),((BigDecimal)row[1]).toPlainString());out.put("originalByCurrency",originals);
  out.put("creditedUsd",total.toPlainString());out.put("groups",totals);
  out.put("userUsd",totals.getOrDefault("USER_SUBMITTED","0"));out.put("legacyUsd",totals.getOrDefault("LEGACY_UNKNOWN","0"));
  out.put("manualUsd",totals.entrySet().stream().filter(e->e.getKey().startsWith("ADMIN_MANUAL")).map(e->new BigDecimal(e.getValue())).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString());out.put("historicalTimeUnknown",records.count(f.and((x,y,z)->z.and(z.equal(x.get("status"),"COMPLETED"),z.isNull(x.get("creditedAt"))))));return out;}
 public static String csv(Object value){String s=value==null?"":String.valueOf(value);if(s.matches("(?s)^[\\s]*[=+@-].*")||s.startsWith("\t")||s.startsWith("\r"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
 @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam Map<String,String> p){permit("export_deposit_orders");Specification<DepositRecord> f=filter(p);
  if(records.count(f)>10000)throw error(400,"请缩小时间范围，最多导出10000条");
  List<DepositRecord> rows=records.findAll(f,PageRequest.of(0,10001,SORT)).getContent();
  if(rows.size()>10000)throw error(400,"请缩小时间范围，最多导出10000条");
  StringBuilder csv=new StringBuilder("\ufeff订单号,UID,用户备注,来源,用途,类型,账户,原币,原币数量,USD金额,状态,订单备注,审核备注,地址,网络,创建时间,审核时间,入账时间\r\n");
  for(DepositRecord d:rows){List<Object> values=Arrays.asList(d.getOrderNo(),d.getUserId(),users.findById(d.getUserId()).map(UserAccount::getRemark).orElse(null),d.getSource()==null?"LEGACY_UNKNOWN":d.getSource(),d.getManualPurpose(),d.getType(),d.getAccountType(),d.getCurrency(),DepositOrderService.decimal(d.getOriginalAmount()),DepositOrderService.decimal(d.getAmount()),d.getStatus(),d.getRemark(),d.getReviewRemark(),d.getAddress(),d.getNetwork(),d.getCreatedAt(),d.getReviewedAt(),d.getCreditedAt());csv.append(values.stream().map(DepositOrderController::csv).collect(Collectors.joining(","))).append("\r\n");}
  return ResponseEntity.ok().header("Content-Disposition","attachment; filename=deposit-orders.csv").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 private Specification<DepositRecord> filter(Map<String,String> p){
  Map<String,List<String>> enums=new HashMap<>();enums.put("status",Arrays.asList("PENDING","COMPLETED","REJECTED"));enums.put("source",Arrays.asList("USER_SUBMITTED","ADMIN_MANUAL","LEGACY_UNKNOWN"));
  enums.put("type",Arrays.asList("bank","digital","manual"));enums.put("accountType",Arrays.asList("FUND","CONTRACT","OPTION"));enums.put("currency",FiatCurrencyService.CURRENCIES);
  for(Map.Entry<String,String> e:p.entrySet()){if(e.getValue().length()>500)throw error(400,"筛选过长");if(enums.containsKey(e.getKey())&&!e.getValue().isEmpty()&&!enums.get(e.getKey()).contains(e.getValue()))throw error(400,"无效筛选");}
  Long agent=BackendAccess.agentId();if(p.containsKey("filterAgentId")&&!p.get("filterAgentId").isEmpty()){Long wanted=Long.valueOf(p.get("filterAgentId"));if(agent!=null&&!agent.equals(wanted))throw error(403,"无权访问");agent=wanted;}final Long scope=agent;
  for(String field:Arrays.asList("created","reviewed","credited")){String from=p.get(field+"From"),to=p.get(field+"To");if(from!=null&&!from.isEmpty()&&to!=null&&!to.isEmpty()){LocalDateTime a=date(from),z=date(to);if(!a.isBefore(z)||java.time.Duration.between(a,z).toDays()>3660)throw error(400,"时间范围无效或超过10年");}}
  return (r,q,b)->{List<Predicate> where=new ArrayList<>();
   if(scope!=null||has(p,"userRemark")){Subquery<Long> sq=q.subquery(Long.class);Root<UserAccount> u=sq.from(UserAccount.class);List<Predicate> terms=new ArrayList<>();if(scope!=null)terms.add(b.equal(u.get("parentUserId"),scope));if(has(p,"userRemark"))terms.add(b.like(u.get("remark"),"%"+escape(p.get("userRemark"))+"%",'!'));sq.select(u.get("id")).where(terms.toArray(new Predicate[0]));where.add(r.get("userId").in(sq));}
   for(String field:Arrays.asList("status","source","type","currency","accountType","orderNo","network"))if(has(p,field)){
    Expression<String> path=r.get(field);if(field.equals("source"))path=b.coalesce(path,"LEGACY_UNKNOWN");if(field.equals("accountType"))path=b.coalesce(path,"FUND");where.add(b.equal(path,p.get(field)));}
   if(has(p,"userId"))where.add(b.equal(r.get("userId"),Long.valueOf(p.get("userId"))));
   if(has(p,"address"))where.add(b.like(r.get("address"),escape(p.get("address"))+"%",'!'));
   for(String field:Arrays.asList("created","reviewed","credited")){if(has(p,field+"From"))where.add(b.greaterThanOrEqualTo(r.get(field+"At"),date(p.get(field+"From"))));if(has(p,field+"To"))where.add(b.lessThan(r.get(field+"At"),date(p.get(field+"To"))));}
   return b.and(where.toArray(new Predicate[0]));};
 }
 private static LocalDateTime date(String value){try{LocalDateTime d=LocalDateTime.parse(value);if(d.getYear()<1900||d.getYear()>2100)throw error(400,"日期超出范围");return d;}catch(java.time.format.DateTimeParseException e){throw error(400,"日期格式无效");}}
 private static boolean has(Map<String,String> p,String k){return p.get(k)!=null&&!p.get(k).isEmpty();}
 private static String escape(String s){return s.replace("!","!!").replace("%","!%").replace("_","!_");}
}
