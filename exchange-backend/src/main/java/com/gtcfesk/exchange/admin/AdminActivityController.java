package com.gtcfesk.exchange.admin;
import com.gtcfesk.exchange.activity.*;
import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.data.domain.*;
import javax.persistence.criteria.Predicate;
import java.util.*;
@RestController @RequestMapping("/api/admin/activities") @RequiredArgsConstructor
public class AdminActivityController {
 private final ActivityService service;private final ActivityCampaignRepository campaigns;private final ActivityDeliveryRepository deliveries;private final TrialFunds funds;private final TrialLedgerRepository ledger;private final AdminUserIdentity userIdentity;
 @GetMapping @AdminPermission(menu="announcement",action="") public Object list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="false") boolean template){return campaigns.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), (root,q,cb)->cb.and(cb.isFalse(root.get("deleted")),cb.equal(root.get("template"),template)), PageRequest.of(Math.max(0,page),50,Sort.by(Sort.Direction.DESC,"id")));}
 @GetMapping(value="/recipients/search",params="!page") @AdminPermission(menu="announcement",action="edit") public Object search(@RequestParam String query){return service.searchRecipients(query);}
 @GetMapping(value="/recipients/search",params="page") @AdminPermission(menu="announcement",action="edit") public Object searchPaged(@org.springframework.web.bind.annotation.ModelAttribute RecipientFilter filter){return service.searchRecipients(filter);}
 @lombok.Data public static class SelectionInput {public String operationId;public RecipientFilter filter;public List<Long> userIds;public Long selectionId;public String sendOperationId;}
 @PostMapping("/{id}/recipients/select-all") @AdminPermission(menu="announcement",action="edit") public Object selectAll(@PathVariable Long id,@RequestBody SelectionInput input){return service.selectAll(id,input.operationId,input.filter,input.userIds);}
 @PostMapping("/{id}/recipients/{selectionId}/append") @AdminPermission(menu="announcement",action="edit") public Object append(@PathVariable Long id,@PathVariable Long selectionId,@RequestBody SelectionInput input){return service.appendSelection(id,selectionId,input.userIds,input.filter);}
 @PostMapping("/{id}/recipients/{selectionId}/remove") @AdminPermission(menu="announcement",action="edit") public Object remove(@PathVariable Long id,@PathVariable Long selectionId,@RequestBody SelectionInput input){return service.removeSelection(id,selectionId,input.userIds);}
 @GetMapping("/{id}/recipients/{selectionId}/selected") @AdminPermission(menu="announcement",action="edit") public Object selected(@PathVariable Long id,@PathVariable Long selectionId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.selectionMembers(id,selectionId,page,size);}
 @PostMapping("/{id}/send-selection") @AdminPermission(menu="announcement",action="edit") public Object sendSelection(@PathVariable Long id,@RequestBody SelectionInput input,Authentication a){return service.sendSelection(id,input.selectionId,input.sendOperationId,a.getName());}
 @PatchMapping("/{id}/content") @AdminPermission(menu="announcement",action="edit") public Object content(@PathVariable Long id,@RequestBody ActivityContentPatch input){return service.saveContent(id,input);}
 @PutMapping("/{id}/auto-send") @AdminPermission(menu="announcement",action="edit") public Object autoSend(@PathVariable Long id,@RequestBody ActivityCampaign input){return service.saveAutoSend(id,input);}
 @DeleteMapping("/{id}") @AdminPermission(menu="announcement",action="delete") public Object delete(@PathVariable Long id){service.delete(id);return Collections.singletonMap("deleted",true);}
 @PostMapping @AdminPermission(menu="announcement",action="create") public Object create(@RequestBody ActivityCampaign input){return service.save(null,input);}
 @PutMapping("/{id}") @AdminPermission(menu="announcement",action="edit") public Object edit(@PathVariable Long id,@RequestBody ActivityCampaign input){return service.save(id,input);}
 @PostMapping("/{id}/send") @AdminPermission(menu="announcement",action="edit") public Object send(@PathVariable Long id,@RequestBody List<Long> ids,Authentication a,@RequestHeader(value="Idempotency-Key",required=false) String key){return service.send(id,ids,a.getName(),key);}
 @GetMapping("/{id}/stats") @AdminPermission(menu="announcement",action="detail") public Object stats(@PathVariable Long id){return service.stats(id);}
 @GetMapping("/{id}/recipients") @AdminPermission(menu="announcement",action="detail") public Object recipients(@PathVariable Long id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="ALL") String state,@RequestParam(required=false) Long userId,@RequestParam(required=false) String userEmail){
  if(!Arrays.asList("ALL","SENT","RECEIVED","OPENED","CLOSED","CLOSED_UNOPENED","CLAIMED").contains(state))throw new BusinessException("筛选状态无效");
  AdminUserIdentity.emailPattern(userEmail);
  Page<ActivityDelivery> result=deliveries.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), (root,q,cb)->{List<Predicate> p=new ArrayList<>();p.add(cb.equal(root.get("campaignId"),id));if(userId!=null)p.add(cb.equal(root.get("userId"),userId));p.add(AdminUserIdentity.emailFilter(root.get("userId"),q,cb,userEmail));
   if("SENT".equals(state))p.add(cb.isNull(root.get("receivedAt")));
   if("RECEIVED".equals(state))p.add(cb.isNotNull(root.get("receivedAt")));
   if("OPENED".equals(state))p.add(cb.isNotNull(root.get("openedAt")));
   if(state.startsWith("CLOSED"))p.add(cb.isNotNull(root.get("closedAt")));
   if("CLOSED_UNOPENED".equals(state))p.add(cb.isNull(root.get("openedAt")));
   if("CLAIMED".equals(state))p.add(cb.isNotNull(root.get("claimedAt")));
   return cb.and(p.toArray(new Predicate[0]));},PageRequest.of(Math.max(0,page),50,Sort.by(Sort.Direction.DESC,"id")));
  return new PageImpl<>(userIdentity.rows(result.getContent()),result.getPageable(),result.getTotalElements());
 }
 @GetMapping("/users/{userId}/account") @AdminPermission(menu="announcement",action="detail") public Object account(@PathVariable Long userId){return funds.snapshot(userId);}
 @GetMapping("/users/{userId}/ledger") @AdminPermission(menu="announcement",action="detail") public Object ledger(@PathVariable Long userId,@RequestParam(defaultValue="0") int page){return ledger.findByTenantIdAndUserIdOrderByIdDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId,PageRequest.of(Math.max(0,page),50));}
}
