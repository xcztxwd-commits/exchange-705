package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.KycRecord;
import com.gtcfesk.exchange.entity.LoanPersonalInfo;
import com.gtcfesk.exchange.repository.KycRecordRepository;
import com.gtcfesk.exchange.repository.LoanPersonalInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LoanPersonalInfoService {
    
    private final LoanPersonalInfoRepository loanPersonalInfoRepository;
    private final KycRecordRepository kycRecordRepository;
    
    @Transactional
    public LoanPersonalInfo submitPersonalInfo(Long userId, String realName, String idNumber, String phone, String address, String idFrontImage, String idBackImage, String handheldImage) {
        // 检查是否有处理中或已通过的记录
        Optional<LoanPersonalInfo> existing = loanPersonalInfoRepository.findByUserId(userId);
        
        LoanPersonalInfo info;
        if (existing.isPresent()) {
            info = existing.get();
            // 如果已通过，不能修改
            if ("APPROVED".equals(info.getStatus())) {
                throw new BusinessException("您的实名认证已通过，无需重复提交");
            }
            // 如果审核中，不能修改
            if ("PENDING".equals(info.getStatus())) {
                throw new BusinessException("您的实名认证正在审核中，请耐心等待");
            }
            // 只有REJECTED状态才能重新提交
        } else {
            info = new LoanPersonalInfo();
            info.setUserId(userId);
        }
        
        info.setRealName(realName);
        info.setIdNumber(idNumber);
        info.setPhone(phone);
        info.setAddress(address);
        
        if (idFrontImage != null && !idFrontImage.isEmpty()) {
            info.setIdFrontImage(idFrontImage);
        }
        if (idBackImage != null && !idBackImage.isEmpty()) {
            info.setIdBackImage(idBackImage);
        }
        if (handheldImage != null && !handheldImage.isEmpty()) {
            info.setHandheldImage(handheldImage);
        }
        
        info.setStatus("PENDING");
        info.setReviewRemark(null);
        
        return loanPersonalInfoRepository.save(info);
    }
    
    /**
     * 获取用户的实名认证信息（用于自动填入）
     */
    public Map<String, String> getKycInfoForLoan(Long userId) {
        Map<String, String> info = new HashMap<>();
        
        // 从实名认证记录获取姓名和身份证号（如果已通过审核）
        Optional<KycRecord> kycRecord = kycRecordRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
        if (kycRecord.isPresent() && "APPROVED".equals(kycRecord.get().getStatus())) {
            KycRecord record = kycRecord.get();
            if (record.getRealName() != null) {
                info.put("realName", record.getRealName());
            }
            if (record.getIdNumber() != null) {
                info.put("idNumber", record.getIdNumber());
            }
        }
        
        return info;
    }
    
    public Map<String, Object> getPersonalInfoStatus(Long userId) {
        Optional<LoanPersonalInfo> info = loanPersonalInfoRepository.findByUserId(userId);
        
        Map<String, Object> result = new HashMap<>();
        if (info.isPresent()) {
            LoanPersonalInfo personalInfo = info.get();
            result.put("verified", "APPROVED".equals(personalInfo.getStatus()));
            Map<String, Object> data = new HashMap<>();
            data.put("realName", personalInfo.getRealName());
            data.put("idNumber", personalInfo.getIdNumber());
            data.put("phone", personalInfo.getPhone());
            data.put("address", personalInfo.getAddress());
            data.put("idFrontImage", personalInfo.getIdFrontImage());
            data.put("idBackImage", personalInfo.getIdBackImage());
            data.put("handheldImage", personalInfo.getHandheldImage());
            result.put("data", data);
        } else {
            result.put("verified", false);
        }
        
        return result;
    }
}

