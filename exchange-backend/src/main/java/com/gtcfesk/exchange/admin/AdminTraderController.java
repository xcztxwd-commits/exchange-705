package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.insights.traders.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController @RequestMapping("/api/admin/insights/traders") @RequiredArgsConstructor
public class AdminTraderController {
    private final TraderService service;
    @ModelAttribute public void noCache(javax.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @GetMapping @AdminPermission(menu="traders") public Map<String,Object> list(@RequestParam(required=false) String q,@RequestParam(required=false) String currency,@RequestParam(required=false) String sourceType,@RequestParam(required=false) Boolean recommended,@RequestParam(required=false) String status,@RequestParam(defaultValue="RECOMMENDED") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(q,currency,sourceType,recommended,status,sort,page,size,true);}
    @GetMapping("/audit") @AdminPermission(menu="traders") public List<TraderAudit> audit(@RequestParam(required=false) String traderId,@RequestParam(defaultValue="0") int page){return service.audits(traderId,page);}
    @GetMapping("/{id}") @AdminPermission(menu="traders") public Map<String,Object> detail(@PathVariable String id){return service.detail(id,true);}
    @GetMapping("/{id}/preview") @AdminPermission(menu="traders") public Map<String,Object> preview(@PathVariable String id){return service.preview(id);}
    @GetMapping("/{id}/equity") @AdminPermission(menu="traders") public Map<String,Object> equity(@PathVariable String id,@RequestParam(defaultValue="ALL") String period,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){return service.equity(id,period,page,size,true);}
    @GetMapping("/{id}/history") @AdminPermission(menu="traders") public Map<String,Object> history(@PathVariable String id,@RequestParam(defaultValue="ALL") String period,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.history(id,period,page,size,true);}
    @GetMapping("/{id}/import-template") @AdminPermission(menu="traders") public Map<String,Object> template(@PathVariable String id,@RequestParam String kind){return service.template(id,kind);}
    @PostMapping @AdminPermission(menu="traders",action="edit") public Map<String,Object> create(@RequestBody TraderData.Edit input){return service.create(input);}
    @PutMapping("/{id}") @AdminPermission(menu="traders",action="edit") public Map<String,Object> edit(@PathVariable String id,@RequestBody TraderData.Edit input){return service.edit(id,input);}
    @PutMapping("/{id}/publication") @AdminPermission(menu="traders",action="publish") public Map<String,Object> publication(@PathVariable String id,@RequestBody TraderData.Change input){return service.publication(id,input);}
    @PutMapping("/{id}/disable") @AdminPermission(menu="traders",action="disable") public Map<String,Object> disable(@PathVariable String id,@RequestBody TraderData.Change input){return service.disable(id,input);}
    @PutMapping("/{id}/recommendation") @AdminPermission(menu="traders",action="sort") public Map<String,Object> recommendation(@PathVariable String id,@RequestBody TraderData.Change input){return service.recommendation(id,input);}
    @PostMapping("/{id}/equity") @AdminPermission(menu="traders",action="equity") public Map<String,Object> createEquity(@PathVariable String id,@RequestBody TraderData.EquityEdit input){return service.saveEquity(id,null,input);}
    @PutMapping("/{id}/equity/{point}") @AdminPermission(menu="traders",action="equity") public Map<String,Object> editEquity(@PathVariable String id,@PathVariable String point,@RequestBody TraderData.EquityEdit input){return service.saveEquity(id,point,input);}
    @DeleteMapping("/{id}/equity/{point}") @AdminPermission(menu="traders",action="equity") public Map<String,Object> removeEquity(@PathVariable String id,@PathVariable String point,@RequestBody TraderData.Change input){return service.remove(id,point,true,input);}
    @PostMapping("/{id}/history") @AdminPermission(menu="traders",action="history") public Map<String,Object> createHistory(@PathVariable String id,@RequestBody TraderData.HistoryEdit input){return service.saveHistory(id,null,input);}
    @PutMapping("/{id}/history/{record}") @AdminPermission(menu="traders",action="history") public Map<String,Object> editHistory(@PathVariable String id,@PathVariable String record,@RequestBody TraderData.HistoryEdit input){return service.saveHistory(id,record,input);}
    @DeleteMapping("/{id}/history/{record}") @AdminPermission(menu="traders",action="history") public Map<String,Object> removeHistory(@PathVariable String id,@PathVariable String record,@RequestBody TraderData.Change input){return service.remove(id,record,false,input);}
    @PostMapping("/{id}/equity/import") @AdminPermission(menu="traders",action="equity") public Map<String,Object> importEquity(@PathVariable String id,@RequestBody TraderData.Import input){return service.importRows(id,true,input);}
    @PostMapping("/{id}/history/import") @AdminPermission(menu="traders",action="history") public Map<String,Object> importHistory(@PathVariable String id,@RequestBody TraderData.Import input){return service.importRows(id,false,input);}
    @ExceptionHandler(TraderService.ImportFailure.class) public ResponseEntity<Map<String,Object>> importError(TraderService.ImportFailure e){Map<String,Object> out=new LinkedHashMap<>();out.put("success",false);out.put("message",e.getMessage());out.put("errors",e.errors);return ResponseEntity.badRequest().body(out);}
}
