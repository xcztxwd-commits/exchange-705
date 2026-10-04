package com.gtcfesk.exchange.insights.news;
import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;import org.springframework.format.annotation.DateTimeFormat;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;import com.gtcfesk.exchange.tenant.TenantContext;import java.time.*;import java.util.*;
@RestController @RequestMapping("/api/insights/news") @RequiredArgsConstructor
public class NewsController {
    private final NewsService service;
    private final Map<Long,long[]> reads=new LinkedHashMap<Long,long[]>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<Long,long[]> e){return size()>256;}};
    private synchronized void budget(){long minute=System.currentTimeMillis()/60000;long[] b=reads.computeIfAbsent(TenantContext.requireTenantId(),t->new long[]{minute,0});if(b[0]!=minute){b[0]=minute;b[1]=0;}if(++b[1]>600)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"新闻查询过于频繁");}
    @GetMapping public Map<String,Object> list(@RequestParam(required=false) String category,@RequestParam(required=false) String sourceId,@RequestParam(required=false) String language,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){budget();return service.list(category,sourceId,language,from,to,page,size,null,false);}
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable String id){budget();return service.detail(id,false);}
}
