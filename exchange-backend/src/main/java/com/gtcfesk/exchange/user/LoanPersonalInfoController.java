package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.LoanPersonalInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/loan/personal-info")
@RequiredArgsConstructor
public class LoanPersonalInfoController {
    
    private final LoanPersonalInfoService loanPersonalInfoService;
    private final FileUploadService fileUploadService;
    
    @PostMapping("/submit")
    public ResponseEntity<?> submitPersonalInfo(
            Authentication auth,
            @RequestParam("realName") String realName,
            @RequestParam("idNumber") String idNumber,
            @RequestParam("phone") String phone,
            @RequestParam("address") String address,
            @RequestParam(value = "idFrontImage", required = false) String idFrontImageStr,
            @RequestParam(value = "idBackImage", required = false) String idBackImageStr,
            @RequestParam(value = "handheldImage", required = false) String handheldImageStr,
            @RequestParam(value = "idFrontImageFile", required = false) MultipartFile idFrontImageFile,
            @RequestParam(value = "idBackImageFile", required = false) MultipartFile idBackImageFile,
            @RequestParam(value = "handheldImageFile", required = false) MultipartFile handheldImageFile) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }
            
            Long userId = Long.parseLong(auth.getName());
            
            // 处理身份证图片 (兼容传文件或者传URL字符串两种方式)
            String frontImageUrl = idFrontImageStr;
            String backImageUrl = idBackImageStr;
            String handheldImageUrl = handheldImageStr;
            
            if (idFrontImageFile != null && !idFrontImageFile.isEmpty()) {
                frontImageUrl = fileUploadService.uploadImage(idFrontImageFile);
            }
            if (idBackImageFile != null && !idBackImageFile.isEmpty()) {
                backImageUrl = fileUploadService.uploadImage(idBackImageFile);
            }
            if (handheldImageFile != null && !handheldImageFile.isEmpty()) {
                handheldImageUrl = fileUploadService.uploadImage(handheldImageFile);
            }
            
            LoanPersonalInfo info = loanPersonalInfoService.submitPersonalInfo(
                    userId, realName, idNumber, phone, address, 
                    frontImageUrl, backImageUrl, handheldImageUrl);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", info);
            resp.put("message", "提交成功，等待审核");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "提交失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    @GetMapping("/kyc-info")
    public ResponseEntity<?> getKycInfoForLoan(Authentication auth) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }
            
            Long userId = Long.parseLong(auth.getName());
            Map<String, String> info = loanPersonalInfoService.getKycInfoForLoan(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", info);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    @GetMapping("/status")
    public ResponseEntity<?> getPersonalInfoStatus(Authentication auth) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }
            
            Long userId = Long.parseLong(auth.getName());
            Map<String, Object> result = loanPersonalInfoService.getPersonalInfoStatus(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.putAll(result);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
}

