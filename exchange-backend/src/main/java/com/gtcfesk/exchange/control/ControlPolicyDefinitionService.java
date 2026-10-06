package com.gtcfesk.exchange.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.tenant.TenantSecrets;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.*;

/** Default assignments use actual tenant policy rows, preserving explicit values and runtime gates. */
@Service @RequiredArgsConstructor
public class ControlPolicyDefinitionService {
 private final ControlPolicyDefinitionRepository definitions;
 private final ControlAuditService audit;
 @org.springframework.beans.factory.annotation.Autowired private TenantRepository tenants;
 @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<TenantManagementService> management;
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final Map<String,String> NAMES=names();
 private static Map<String,String> names(){
  Map<String,String> names=new LinkedHashMap<>();
  names.put("feature.registration","用户注册");names.put("feature.option","期权交易");names.put("feature.contract","合约交易");
  names.put("feature.financial","理财");names.put("feature.loan","贷款");names.put("feature.activity","活动");names.put("feature.simulation","模拟交易");
  names.put("feature.deposit","充值");names.put("feature.withdraw","提现");names.put("feature.support","站内客服");
  names.put("feature.external_support","外部客服");names.put("feature.inbox","站内信");names.put("feature.agent","代理功能");
  names.put("config.support.channel","客服渠道");names.put("retention.auto_delete_enabled","历史会话自动清理");names.put("retention.keep_days","历史会话保留天数");
  return Collections.unmodifiableMap(names);
 }
 private static Long actor(){
  ControlIdentity identity=ControlIdentity.current();
  if(identity==null||identity.getTenantId()!=null||identity.getAccessSessionId()!=null)throw new AccessDeniedException("需要独立总控身份");
  return ControlIdentity.actorId();
 }
 @Transactional(readOnly=true) public List<View> list(){
  actor();Map<String,View> result=new LinkedHashMap<>();NAMES.keySet().forEach(key->result.put(key,builtin(key)));
  definitions.findAll().stream().sorted(Comparator.comparing(ControlPolicyDefinition::getKey)).forEach(row->result.put(row.getKey(),view(row)));
  return new ArrayList<>(result.values());
 }
 @Transactional public View save(Input input){
  Long actor=actor();validate(input);lockDefaults();
  ControlPolicyDefinition row=definitions.findById(input.key).orElse(null);
  if(!Objects.equals(input.version,row==null?null:row.getVersion()))throw conflict();
  if(row==null){row=new ControlPolicyDefinition();row.setKey(input.key);}
  row.setName(input.name.trim());row.setDefaultValue(input.defaultValue);
  try{row.setOptionsJson(JSON.writeValueAsString(input.options));}catch(IOException failure){throw new IllegalArgumentException("选项值格式无效");}
  try{definitions.saveAndFlush(row);}catch(DataIntegrityViolationException concurrentCreate){throw conflict();}
  View result=view(row);
  if(result.tenantEditable){
   // ponytail: a small tenant catalog uses one atomic update; use a controlled batch workflow if it grows.
   List<Tenant> targets=new ArrayList<>(tenants.findAll());targets.sort(Comparator.comparing(Tenant::getId));
   for(Tenant tenant:targets){if(management.getObject().applyDefault(tenant.getId(),input.key,input.defaultValue))result.appliedTenants++;else result.retainedTenants++;}
  }
  audit.record(actor,null,null,"POLICY_DEFINITION_UPDATE",input.key,"SUCCESS","version="+row.getVersion()+";options="+input.options.size()+";applied="+result.appliedTenants+";retained="+result.retainedTenants,TenantManagementService.policyReason(input.reason));
  return result;
 }
 private void lockDefaults(){
  // Serialize catalog edits and tenant initialization without adding locks to money/order readers.
  if(!definitions.lockDefaults().isPresent())throw new IllegalStateException("默认策略锚点缺失，请先验收策略表迁移");
 }
 @Transactional public Map<String,String> initialDefaults(){
  actor();lockDefaults();Map<String,String> values=new LinkedHashMap<>();
  for(View row:list())if(row.tenantEditable){Input input=new Input();input.key=row.key;input.name=row.name;input.options=row.options;input.defaultValue=row.defaultValue;validate(input);values.put(row.key,row.defaultValue);}
  if("internal".equals(values.get("config.support.channel"))){requireAllowedValue("feature.support","true");requireAllowedValue("feature.external_support","false");values.put("feature.support","true");values.put("feature.external_support","false");}
  return values;
 }
 @Transactional(readOnly=true) public void requireAllowedValue(String key,String value){
  actor();View definition=definitions.findById(key).map(ControlPolicyDefinitionService::view).orElseGet(()->builtin(key));
  if(definition!=null&&!definition.options.isEmpty()&&!definition.options.contains(value))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"策略值不在授权策略页面设置的选项中，请刷新后选择");
 }
 private static void validate(Input input){
  if(input==null||input.key==null||!input.key.matches("[a-z][a-z0-9_.-]{1,127}"))throw new IllegalArgumentException("策略键无效");
  boolean feature=input.key.startsWith("feature.")&&TenantPolicyService.FEATURES.contains(input.key.substring(8));
  boolean config=input.key.startsWith("config.")&&input.key.length()>7&&!input.key.startsWith("config.platform.");
  View builtin=builtin(input.key);boolean retention=builtin!=null&&builtin.fixedValues;
  if((!feature&&!config&&!retention)||TenantSecrets.secret(input.key)||input.key.contains("credential"))throw new IllegalArgumentException("只支持已实现功能或非密钥配置策略");
  if(input.name==null||input.name.trim().isEmpty()||input.name.length()>128)throw new IllegalArgumentException("请填写策略名字（最多128字符）");
  if(input.defaultValue==null||input.defaultValue.length()>8192||input.options==null||input.options.size()>64)throw new IllegalArgumentException("策略默认值或选项无效");
  Set<String> seen=new HashSet<>();for(String value:input.options){if(value==null||value.length()>512||!seen.add(value))throw new IllegalArgumentException("选项值不能重复，每项最多512字符");}
  if(!input.options.isEmpty()&&!input.options.contains(input.defaultValue))throw new IllegalArgumentException("默认值必须在选项值中");
  if(feature&&(!input.options.contains("false")||!Arrays.asList("false","true").containsAll(input.options)))throw new IllegalArgumentException("功能选项只允许 true/false，且必须保留 false 以关闭功能");
  if("config.support.channel".equals(input.key)&&(!input.options.contains("off")||!Arrays.asList("off","internal","external").containsAll(input.options)))throw new IllegalArgumentException("客服渠道只允许 off/internal/external，且必须保留 off");
  if(retention&&(!builtin.options.equals(input.options)||!builtin.defaultValue.equals(input.defaultValue)))throw new IllegalArgumentException("留存规则需经客服监管与留存专页维护，此处只能修改名字");
  TenantManagementService.policyReason(input.reason);
 }
 private static View builtin(String key){
  String name=NAMES.get(key);if(name==null)return null;
  if(key.startsWith("feature."))return new View(key,name,Arrays.asList("false","true"),"false",null,true,false,"");
  if("config.support.channel".equals(key))return new View(key,name,Arrays.asList("off","internal","external"),"off",null,true,false,"");
  if("retention.keep_days".equals(key))return new View(key,name,Collections.singletonList("365"),"365",null,false,true,"当前实际保留期固定为365天；此处只维护名字，不改变清理行为。");
  return new View(key,name,Arrays.asList("false","true"),"false",null,false,true,"通过“客服监管与留存”预览、确认和恢复证明后设置；此处只维护名字。");
 }
 private static View view(ControlPolicyDefinition row){
  View builtin=builtin(row.getKey());
  try{
   List<String> options=JSON.readValue(row.getOptionsJson(),new TypeReference<List<String>>(){});
   return new View(row.getKey(),row.getName(),options,row.getDefaultValue(),row.getVersion(),builtin==null||builtin.tenantEditable,builtin!=null&&builtin.fixedValues,builtin==null?"":builtin.note);
  }catch(IOException failure){throw new IllegalStateException("授权策略定义读取失败",failure);}
 }
 private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"策略定义已变更，请刷新后重试");}
 public static class Input {public String key,name,defaultValue,reason;public List<String> options;public Long version;}
 public static class View {
  public final String key,name,defaultValue,note;public final List<String> options;public final Long version;public final boolean tenantEditable,fixedValues;
  public int appliedTenants,retainedTenants;
  View(String key,String name,List<String> options,String defaultValue,Long version,boolean tenantEditable,boolean fixedValues,String note){this.key=key;this.name=name;this.options=options;this.defaultValue=defaultValue;this.version=version;this.tenantEditable=tenantEditable;this.fixedValues=fixedValues;this.note=note;}
 }
}
