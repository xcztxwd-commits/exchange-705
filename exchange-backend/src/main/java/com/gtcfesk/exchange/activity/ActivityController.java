package com.gtcfesk.exchange.activity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.data.domain.*;
import java.util.*;
@RestController @RequestMapping("/api/activity") @RequiredArgsConstructor
public class ActivityController {
 private final ActivityService service;private final TrialFunds funds;private final TrialLedgerRepository ledger;
 @GetMapping("/inbox") public Object inbox(Authentication a,@RequestParam(defaultValue="0") int page){return service.inbox(Long.valueOf(a.getName()),page);}
 @PostMapping("/messages/{id}/event") public Object event(Authentication a,@PathVariable Long id,@RequestBody Map<String,String> body){return service.event(Long.valueOf(a.getName()),id,body.get("type"));}
 @PostMapping("/messages/{id}/claim") public Object claim(Authentication a,@PathVariable Long id){return service.claim(Long.valueOf(a.getName()),id);}
 @GetMapping("/messages/{id}") public Object message(Authentication a,@PathVariable Long id){return service.message(Long.valueOf(a.getName()),id);}
 @GetMapping("/account") public Object account(Authentication a){Long user=Long.valueOf(a.getName());Map<String,Object> m=new LinkedHashMap<>();m.put("account",funds.snapshot(user));m.put("canTrade",funds.canTrade(user));return m;}
 @GetMapping("/ledger") public Object ledger(Authentication a,@RequestParam(defaultValue="0") int page){return ledger.findByUserIdOrderByIdDesc(Long.valueOf(a.getName()),PageRequest.of(Math.max(0,page),20));}
}
