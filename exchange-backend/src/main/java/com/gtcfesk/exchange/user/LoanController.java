package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.LoanRecord;
import com.gtcfesk.exchange.entity.LoanSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/loan")
@RequiredArgsConstructor
public class LoanController {

    private final LoanService loanService;
    private final FileUploadService fileUploadService;

    @GetMapping("/settings")
    public ResponseEntity<?> getLoanSettings() {
        try {
            List<LoanSetting> settings = loanService.getAvailableLoanSettings();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", settings);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @PostMapping("/apply")
    public ResponseEntity<?> applyLoan(
            Authentication auth,
            @RequestBody Map<String, Object> request) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }

            Long userId = Long.parseLong(auth.getName());
            BigDecimal amount = new BigDecimal(request.get("amount").toString());
            Long settingId = Long.parseLong(request.get("settingId").toString());
            
            String realName = request.get("realName") != null ? request.get("realName").toString() : null;
            String idNumber = request.get("idNumber") != null ? request.get("idNumber").toString() : null;
            String phone = request.get("phone") != null ? request.get("phone").toString() : null;
            String address = request.get("address") != null ? request.get("address").toString() : null;

            LoanRecord record = loanService.createLoan(userId, amount, settingId, realName, idNumber, phone, address);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", record);
            resp.put("message", "贷款申请提交成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "申请失败: " + e.getMessage());
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
            Map<String, String> info = loanService.getKycInfoForLoan(userId);

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

    @PostMapping("/sign")
    public ResponseEntity<?> signContract(
            Authentication auth,
            @RequestBody Map<String, Object> request) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }

            Long loanId = Long.parseLong(request.get("loanId").toString());
            String signatureImage = request.get("signatureImage").toString();
            
            // 如果签名图片是base64格式，尝试上传到服务器
            String finalSignatureImage = signatureImage;
            if (signatureImage != null && signatureImage.startsWith("data:image")) {
                try {
                    // 将base64转换为MultipartFile并上传
                    finalSignatureImage = fileUploadService.uploadBase64Image(signatureImage);
                } catch (Exception e) {
                    // 如果上传失败，直接使用base64（但可能太长，建议使用TEXT类型）
                    // 这里先尝试截断或使用原值
                    if (signatureImage.length() > 2000) {
                        throw new RuntimeException("签名图片过大，请重新签名");
                    }
                    finalSignatureImage = signatureImage;
                }
            }

            LoanRecord record = loanService.signContract(loanId, finalSignatureImage);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", record);
            resp.put("message", "合同签署成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "签署失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @GetMapping("/list")
    public ResponseEntity<?> getMyLoans(Authentication auth) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }

            Long userId = Long.parseLong(auth.getName());
            List<LoanRecord> loans = loanService.getUserLoans(userId);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", loans);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getLoan(@PathVariable Long id, Authentication auth) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }

            LoanRecord record = loanService.getLoanById(id);
            Long userId = Long.parseLong(auth.getName());

            if (!record.getUserId().equals(userId)) {
                throw new RuntimeException("无权访问该贷款记录");
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", record);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @GetMapping("/total")
    public ResponseEntity<?> getTotalLoanAmount(Authentication auth) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }

            Long userId = Long.parseLong(auth.getName());
            BigDecimal totalAmount = loanService.getTotalLoanAmount(userId);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("totalAmount", totalAmount);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @PostMapping("/repay/{id}")
    public ResponseEntity<?> repayLoan(@PathVariable Long id, Authentication auth) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }

            Long userId = Long.parseLong(auth.getName());
            LoanRecord record = loanService.earlyRepayment(id, userId);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", record);
            resp.put("message", "还款成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "还款失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
}

