package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.market.HistorySourceRestore;
import lombok.Getter;
import lombok.Setter;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/admin/ai-control/{symbol}/history-restore")
public class AdminHistoryRestoreController {
    private final ForexQuoteMarketService market;
    private final HistorySourceRestore restore;
    public AdminHistoryRestoreController(ForexQuoteMarketService market,HistorySourceRestore restore) { this.market=market; this.restore=restore; }
    @Getter @Setter public static class Range {
        @NotNull private Long from;
        @NotNull private Long to;
        @NotBlank @Size(max=64) private String timezone;
    }
    @Getter @Setter public static class Confirmation {
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{36}") private String previewToken;
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{16,64}") private String requestKey;
    }
    @Getter @Setter public static class GapRange extends Range {
        @NotBlank @Pattern(regexp="1m|5m|15m|30m|1h") private String period;
    }
    @GetMapping("/gaps") @AdminPermission(menu="ai_control",action="")
    public Map<String,Object> gaps(@PathVariable Long symbol, @RequestParam long from, @RequestParam long to,
            @RequestParam String period, @RequestParam String timezone) { return market.checkSourceGaps(symbol, from, to, period, timezone); }
    @PostMapping("/gaps/repair") @AdminPermission(menu="ai_control",action="restore_history")
    public Map<String,Object> repairGaps(@PathVariable Long symbol, @Valid @RequestBody GapRange range) {
        return market.queueSourceGaps(symbol, range.getFrom(), range.getTo(), range.getPeriod(), range.getTimezone());
    }
    @GetMapping("/chart") @AdminPermission(menu="ai_control",action="")
    public Map<String,Object> chart(@PathVariable Long symbol,@RequestParam long from,@RequestParam long to,@RequestParam String timezone) { return market.historyRestoreChart(symbol,from,to,timezone); }
    @PostMapping("/preview") @AdminPermission(menu="ai_control",action="restore_history")
    public Map<String,Object> preview(@PathVariable Long symbol,@Valid @RequestBody Range range) { return market.historyRestorePreview(symbol,range.from,range.to,range.timezone); }
    @PostMapping("/source") @AdminPermission(menu="ai_control",action="restore_history")
    public Map<String,Object> source(@PathVariable Long symbol,@Valid @RequestBody Range range) { return market.backfillHistoryRestore(symbol,range.from,range.to,range.timezone); }
    @PostMapping("/jobs") @AdminPermission(menu="ai_control",action="restore_history")
    public ResponseEntity<Map<String,Object>> accept(@PathVariable Long symbol,@Valid @RequestBody Confirmation request) { if(!"RESTORE".equals(restore.query(symbol,request.previewToken,null).get("kind"))) throw new com.gtcfesk.exchange.common.BusinessException("请从撤销入口提交"); return ResponseEntity.accepted().body(market.acceptHistoryRestore(symbol,request.previewToken,request.requestKey)); }
    @GetMapping("/jobs") @AdminPermission(menu="ai_control",action="")
    public Object jobs(@PathVariable Long symbol,@RequestParam(required=false) String requestKey) { market.controlStatus(symbol); return requestKey==null?restore.list(symbol):restore.query(symbol,null,requestKey); }
    @GetMapping("/jobs/{id}") @AdminPermission(menu="ai_control",action="")
    public Map<String,Object> job(@PathVariable Long symbol,@PathVariable String id) { market.controlStatus(symbol); return restore.details(symbol,id); }
    @PostMapping("/jobs/{id}/undo-preview") @AdminPermission(menu="ai_control",action="undo_history_restore")
    public Map<String,Object> undoPreview(@PathVariable Long symbol,@PathVariable String id) { market.controlStatus(symbol); return restore.undoPreview(symbol,id); }
    @PostMapping("/jobs/{id}/undo") @AdminPermission(menu="ai_control",action="undo_history_restore")
    public ResponseEntity<Map<String,Object>> undo(@PathVariable Long symbol,@PathVariable String id,@Valid @RequestBody Confirmation request) { Map<String,Object> preview=restore.query(symbol,request.previewToken,null); if(!id.equals(preview.get("undoOf")) || !"UNDO".equals(preview.get("kind"))) throw new com.gtcfesk.exchange.common.BusinessException("撤销预览不匹配"); return ResponseEntity.accepted().body(market.acceptHistoryRestore(symbol,request.previewToken,request.requestKey)); }
    @PostMapping("/jobs/{id}/retry") @AdminPermission(menu="ai_control",action="restore_history")
    public Map<String,Object> retry(@PathVariable Long symbol,@PathVariable String id) { market.controlStatus(symbol); if("UNDO".equals(restore.query(symbol,id,null).get("kind"))) throw new com.gtcfesk.exchange.common.BusinessException("撤销任务请从撤销入口重试"); return restore.retry(symbol,id); }
    @PostMapping("/jobs/{id}/undo-retry") @AdminPermission(menu="ai_control",action="undo_history_restore")
    public Map<String,Object> undoRetry(@PathVariable Long symbol,@PathVariable String id) { market.controlStatus(symbol); if(!"UNDO".equals(restore.query(symbol,id,null).get("kind"))) throw new com.gtcfesk.exchange.common.BusinessException("请从恢复入口重试"); return restore.retry(symbol,id); }
}
