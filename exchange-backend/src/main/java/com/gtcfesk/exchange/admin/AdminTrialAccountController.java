package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.admin.dto.UpdateTrialAccountRequest;
import com.gtcfesk.exchange.config.AdminPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/users/{userId}/trial-account")
public class AdminTrialAccountController {
 private final AdminTrialAccountService service;
 @GetMapping @AdminPermission(menu="users",action="modify_balance")
 public Object account(@PathVariable("userId") Long userId){return service.account(userId);}
 @PutMapping @AdminPermission(menu="users",action="modify_balance")
 public Object update(@PathVariable("userId") Long userId,@RequestBody UpdateTrialAccountRequest input){return service.update(userId,input);}
}
