package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.KycRecord;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.KycRecordRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/kyc")
@RequiredArgsConstructor
public class KycController {
    private final KycRecordRepository kycRecordRepository;
    private final UserAccountRepository userAccountRepository;
    private final FileUploadService fileUploadService;
    
    // 提交实名认证
    @PostMapping("/submit")
    public ResponseEntity<?> submitKyc(
            Authentication auth,
            @RequestParam("realName") String realName,
            @RequestParam("idNumber") String idNumber,
            @RequestParam(value = "idFrontImageStr", required = false) String idFrontImageStr,
            @RequestParam(value = "idBackImageStr", required = false) String idBackImageStr,
            @RequestParam(value = "idFrontImage", required = false) MultipartFile idFrontImage,
            @RequestParam(value = "idBackImage", required = false) MultipartFile idBackImage) {
        
        if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
            throw new BusinessException("未登录");
        }
        
        Long userId = Long.parseLong(auth.getName());
        
        // 检查是否已有审核中的申请
        Optional<KycRecord> existingRecord = kycRecordRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
        if (existingRecord.isPresent()) {
            KycRecord record = existingRecord.get();
            if ("PENDING".equals(record.getStatus())) {
                throw new BusinessException("您已有审核中的申请，请等待审核");
            }
            if ("APPROVED".equals(record.getStatus())) {
                throw new BusinessException("您已完成实名认证");
            }
        }
        
        // 上传图片 (支持传文件或传URL字符串)
        String frontImageUrl = idFrontImageStr;
        String backImageUrl = idBackImageStr;
        
        if (idFrontImage != null && !idFrontImage.isEmpty()) {
            frontImageUrl = fileUploadService.uploadImage(idFrontImage);
        }
        if (idBackImage != null && !idBackImage.isEmpty()) {
            backImageUrl = fileUploadService.uploadImage(idBackImage);
        }
        
        if (frontImageUrl == null || backImageUrl == null) {
            throw new BusinessException("请上传完整的证件照片");
        }
        
        // 创建实名认证记录
        KycRecord kycRecord = new KycRecord();
        kycRecord.setUserId(userId);
        kycRecord.setRealName(realName);
        kycRecord.setIdNumber(idNumber);
        kycRecord.setIdFrontImage(frontImageUrl);
        kycRecord.setIdBackImage(backImageUrl);
        kycRecord.setStatus("PENDING");
        
        kycRecordRepository.save(kycRecord);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "提交成功，等待审核");
        return ResponseEntity.ok(result);
    }
    
    // 查询当前用户的实名认证状态
    @GetMapping("/status")
    public ResponseEntity<?> getKycStatus(Authentication auth) {
        if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
            throw new BusinessException("未登录");
        }
        
        Long userId = Long.parseLong(auth.getName());
        
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        
        Optional<KycRecord> latestRecord = kycRecordRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("kycStatus", user.getKycStatus() != null ? user.getKycStatus() : "NOT_VERIFIED");
        
        if (latestRecord.isPresent()) {
            KycRecord record = latestRecord.get();
            Map<String, Object> recordInfo = new HashMap<>();
            recordInfo.put("status", record.getStatus());
            recordInfo.put("realName", record.getRealName());
            recordInfo.put("reviewRemark", record.getReviewRemark());
            recordInfo.put("createdAt", record.getCreatedAt());
            recordInfo.put("reviewedAt", record.getReviewedAt());
            result.put("latestRecord", recordInfo);
        }
        
        return ResponseEntity.ok(result);
    }
}

