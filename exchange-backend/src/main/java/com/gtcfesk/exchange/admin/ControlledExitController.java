package com.gtcfesk.exchange.admin;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.gtcfesk.exchange.config.AdminPermission;
/** Independent control can enter this surface only with a target-tenant access session. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/admin/controlled-exits")
public class ControlledExitController {
 private final ControlledExitService service;
 @PostMapping("/loan/{id}/repay") @AdminPermission(menu="loan_review",action="controlled_exit") public Object repay(@PathVariable Long id,@RequestBody ControlledExitService.Input body){return service.execute("loan",id,"repay",body);}
 @PostMapping("/contract/{id}/close") @AdminPermission(menu="orders",action="close_order") public Object close(@PathVariable Long id,@RequestBody ControlledExitService.Input body){return service.execute("contract",id,"close",body);}
 @PostMapping("/contract/{id}/cancel") @AdminPermission(menu="orders",action="cancel_order") public Object cancel(@PathVariable Long id,@RequestBody ControlledExitService.Input body){return service.execute("contract",id,"cancel",body);}
 @PostMapping("/contract/{id}/risk") @AdminPermission(menu="orders",action="risk_exit") public Object risk(@PathVariable Long id,@RequestBody ControlledExitService.Input body){return service.execute("contract",id,"risk",body);}
 @PostMapping("/option/{id}/close") @AdminPermission(menu="orders",action="close_order") public Object option(@PathVariable Long id,@RequestBody ControlledExitService.Input body){return service.execute("option",id,"close",body);}
 @PostMapping("/financial/{id}/redeem") @AdminPermission(menu="financial_orders",action="controlled_exit") public Object redeem(@PathVariable Long id,@RequestBody ControlledExitService.Input body){return service.execute("financial",id,"redeem",body);}
}
