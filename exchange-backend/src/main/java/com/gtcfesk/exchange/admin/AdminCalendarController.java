package com.gtcfesk.exchange.admin;
import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.insights.*;
import com.gtcfesk.exchange.config.AdminPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.*;
import java.util.*;
@RestController @RequestMapping("/api/admin/insights/calendar") @RequiredArgsConstructor
public class AdminCalendarController {
    private final CalendarService service;
    private final CalendarSync sync;
    private final ObjectMapper json;
    @GetMapping @AdminPermission(menu="calendar") public Map<String,Object> list(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(required=false) String country,@RequestParam(required=false) String importance,@RequestParam(required=false) String metric,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size){Map<String,Object> out=service.list(from,to,country,importance,metric,status,page,size,true);out.put("sources",sync.status());return out;}
    @GetMapping("/sources") @AdminPermission(menu="calendar") public List<Map<String,Object>> sources(){return sync.status();}
    @GetMapping("/sources/{source}/history") @AdminPermission(menu="calendar") public List<CalendarSourceUpdate> history(@PathVariable String source,@RequestParam(defaultValue="0") int page){return sync.history(source,page);}
    @PostMapping("/sources/{source}/sync") @AdminPermission(menu="calendar",action="sync") public Map<String,Object> sync(@PathVariable String source){CalendarService.actor();return sync.sync(source);}
    @GetMapping("/{id}") @AdminPermission(menu="calendar") public Map<String,Object> detail(@PathVariable String id){return service.detail(id,true);}
    @GetMapping("/{id}/audit") @AdminPermission(menu="calendar") public List<CalendarAudit> audit(@PathVariable String id,@RequestParam(defaultValue="0") int page){return service.audits(id,page);}
    @PutMapping("/{id}") @AdminPermission(menu="calendar",action="edit") public Map<String,Object> edit(@PathVariable String id,@RequestBody CalendarService.Edit input){return service.edit(id,input);}
    @PostMapping("/{id}/publication") @AdminPermission(menu="calendar",action="publish") public Map<String,Object> publish(@PathVariable String id,@RequestBody CalendarService.Publication input){return service.publish(id,input);}
    public static class Import {public String sourceId,format,content,material;public boolean verified;}
    @PostMapping("/import") @AdminPermission(menu="calendar",action="import") public Map<String,Object> importMaterial(@RequestBody Import input)throws Exception{
        CalendarService.actor();if(input==null||!input.verified||input.material==null||input.material.trim().isEmpty()||input.content==null||input.content.length()>2000000||input.content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2000000)throw CalendarData.bad("须核验合法官方材料、填写依据，并限制内容大小");CalendarParser.Batch b;
        if("ICS".equals(input.format)){if(!Arrays.asList("BLS_CALENDAR","BEA_CALENDAR").contains(input.sourceId))throw CalendarData.bad("仅支持 BLS / BEA 官方 ICS");b=CalendarParser.ics(input.sourceId.startsWith("BLS")?"BLS":"BEA",input.content,Instant.now());}
        else if("VERIFIED_JSON".equals(input.format)){try{JsonNode array=json.readTree(input.content);if(array==null||!array.isArray()||array.size()<1||array.size()>200)throw CalendarData.bad("核验材料须为1至200条数组");b=new CalendarParser.Batch();for(JsonNode row:array){CalendarData d=json.treeToValue(row,CalendarData.class);if(d==null)throw CalendarData.bad("事件不能为空");d.sourceAsOf=Instant.now();d.sourceChannel="VERIFIED_OFFICIAL_MATERIAL";if(d.actual!=null||d.previous!=null)d.actualBasis="OFFICIAL_MATERIAL";d.validate();b.events.add(d);}}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw CalendarData.bad("核验 JSON 格式无效");}}
        else throw CalendarData.bad("只支持 ICS / VERIFIED_JSON");Map<String,Object> out=new LinkedHashMap<>();out.put("changed",service.importBatch(b,true,input.material));out.put("eventIds",b.events.stream().map(d->d.eventId).collect(java.util.stream.Collectors.toList()));out.put("importKind","VERIFIED_MANUAL_IMPORT");out.put("publication","NEW_ENTRIES_ARE_DRAFTS");return out;
    }
}
