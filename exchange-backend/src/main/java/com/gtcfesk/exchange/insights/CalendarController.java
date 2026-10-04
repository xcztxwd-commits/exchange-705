package com.gtcfesk.exchange.insights;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.time.*;
import java.util.*;
@RestController @RequestMapping("/api/insights/calendar") @RequiredArgsConstructor
public class CalendarController {
    private final CalendarService service;
    private final CalendarSync sync;
    private final Map<Long,long[]> reads=new LinkedHashMap<Long,long[]>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<Long,long[]> e){return size()>256;}};
    private synchronized void budget(){long minute=System.currentTimeMillis()/60000;long[] b=reads.computeIfAbsent(TenantContext.requireTenantId(),t->new long[]{minute,0});if(b[0]!=minute){b[0]=minute;b[1]=0;}if(++b[1]>600)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"日历查询过于频繁");}
    @GetMapping public Map<String,Object> list(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(required=false) String country,@RequestParam(required=false) String importance,@RequestParam(required=false) String metric,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size){budget();Map<String,Object> out=service.list(from,to,country,importance,metric,status,page,size,false);out.put("sources",sync.status());return out;}
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable String id){budget();Map<String,Object> out=service.detail(id,false);out.put("sources",sync.status());return out;}
    @GetMapping(value="/{id}.ics",produces="text/calendar;charset=UTF-8") public ResponseEntity<String> ics(@PathVariable String id){budget();return ResponseEntity.ok().header("Content-Disposition","attachment; filename=calendar-event.ics").body(service.ics(id));}
}
