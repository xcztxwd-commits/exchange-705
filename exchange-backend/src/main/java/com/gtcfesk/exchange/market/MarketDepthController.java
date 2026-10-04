package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/market/depth")
public class MarketDepthController {
    @Autowired MarketDepthService depth;
    @GetMapping("/{symbol}") public ResponseEntity<?> read(@PathVariable String symbol,@RequestParam(defaultValue="20") int levels,@RequestParam(required=false) String marketType){
        try {Map<String,Object> view=depth.read(symbol,levels,marketType,"rest:"+TenantContext.requireTenantId()+":"+symbol+":"+marketType);
            String reason=Objects.toString(view.get("reason"),"");return ResponseEntity.status("unknown_or_disabled_instrument".equals(reason)?404:"subscription_limit".equals(reason)?429:200).body(view);
        }catch(IllegalArgumentException invalid){Map<String,Object> r=new LinkedHashMap<>();r.put("success",false);r.put("status","ERROR");r.put("reason","invalid_parameters");r.put("message",invalid.getMessage());return ResponseEntity.badRequest().body(r);}
    }
}
