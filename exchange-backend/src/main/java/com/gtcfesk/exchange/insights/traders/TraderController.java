package com.gtcfesk.exchange.insights.traders;

import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController @RequestMapping("/api/insights/traders") @RequiredArgsConstructor
public class TraderController {
    private final TraderService service;
    private final Map<Long,long[]> reads=new LinkedHashMap<Long,long[]>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<Long,long[]> e){return size()>256;}};
    private synchronized void budget(){long minute=System.currentTimeMillis()/60000;long[] b=reads.computeIfAbsent(TenantContext.requireTenantId(),t->new long[]{minute,0});if(b[0]!=minute){b[0]=minute;b[1]=0;}if(++b[1]>600)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"人物查询过于频繁");}
    @ModelAttribute public void noCache(javax.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @GetMapping public Map<String,Object> list(@RequestParam(required=false) String q,@RequestParam(required=false) String currency,@RequestParam(required=false) String sourceType,@RequestParam(required=false) Boolean recommended,@RequestParam(defaultValue="RECOMMENDED") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){budget();return service.list(q,currency,sourceType,recommended,null,sort,page,size,false);}
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable String id){budget();return service.detail(id,false);}
    @GetMapping("/{id}/equity") public Map<String,Object> equity(@PathVariable String id,@RequestParam(defaultValue="ALL") String period,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){budget();return service.equity(id,period,page,size,false);}
    @GetMapping("/{id}/history") public Map<String,Object> history(@PathVariable String id,@RequestParam(defaultValue="ALL") String period,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){budget();return service.history(id,period,page,size,false);}
}
