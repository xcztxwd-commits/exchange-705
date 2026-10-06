package com.gtcfesk.exchange.control;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.TablePreferenceValidation;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;
/** Only an independent CONTROL identity can address this repository. */
@RestController @RequestMapping(value="/api/control/table-preferences", produces="application/json;charset=UTF-8") @RequiredArgsConstructor
public class ControlTablePreferenceController {
 private final ControlTablePreferenceRepository repository;
 private final ObjectMapper mapper;
 private final ControlAuditService audit;
 private long actor(){
  Authentication a=SecurityContextHolder.getContext().getAuthentication();ControlIdentity i=ControlIdentity.current();
  if(a==null||!a.isAuthenticated()||a.getAuthorities().stream().noneMatch(r->"ROLE_CONTROL".equals(r.getAuthority()))||i==null||i.getActorId()==null||i.getActorId()<=0||i.getAccessSessionId()!=null||i.getTenantId()!=null||TenantContext.currentTenantId()!=null)throw new AccessDeniedException("需要独立总控身份");
  return i.getActorId();
 }
 @GetMapping("/{table}") @Transactional(readOnly=true)
 public JsonNode get(@PathVariable String table) throws java.io.IOException{
  long actor=actor();String key=actor+":"+TablePreferenceValidation.table(table);
  Optional<ControlTablePreference> saved=repository.findById(key);
  return saved.isPresent()?mapper.readTree(saved.get().getColumnsJson()):mapper.createArrayNode();
 }
 @PutMapping("/{table}") @Transactional
 public Map<String,Boolean> save(@PathVariable String table,@RequestBody JsonNode columns){
  long actor=actor();TablePreferenceValidation.table(table);TablePreferenceValidation.columns(columns);
  ControlTablePreference value=new ControlTablePreference();value.setId(actor+":"+table);value.setActorId(actor);value.setTableKey(table);value.setColumnsJson(columns.toString());repository.saveAndFlush(value);
  audit.record(actor,null,null,"CONTROL_TABLE_PREFERENCES",table,"SUCCESS","presentation columns only",null);
  return Collections.singletonMap("success",true);
 }
}
