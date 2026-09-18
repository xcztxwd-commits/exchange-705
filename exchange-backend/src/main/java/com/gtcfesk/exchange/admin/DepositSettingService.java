package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.DepositSetting;
import com.gtcfesk.exchange.repository.DepositSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepositSettingService {

    private final DepositSettingRepository depositSettingRepository;

    public List<DepositSetting> getAllSettings() {
        return depositSettingRepository.findAll();
    }

    @Transactional
    public DepositSetting createSetting(DepositSetting setting) {
        if (setting.getType() == null || setting.getType().isEmpty()) {
            setting.setType("digital"); // 默认为数字货币
        }
        
        if ("digital".equals(setting.getType())) {
            // 数字货币验证
            if (setting.getNetwork() == null || setting.getNetwork().isEmpty()) {
                throw new IllegalArgumentException("网络/币种不能为空");
            }
            if (setting.getAddress() == null || setting.getAddress().isEmpty()) {
                throw new IllegalArgumentException("充值地址不能为空");
            }
            // 检查是否已存在
            if (depositSettingRepository.findByNetworkAndType(setting.getNetwork(), "digital").isPresent()) {
                throw new IllegalArgumentException("该网络/币种已存在");
            }
        } else if ("bank".equals(setting.getType())) {
            // 银行卡验证
            if (setting.getBankName() == null || setting.getBankName().isEmpty()) {
                throw new IllegalArgumentException("开户银行不能为空");
            }
            if (setting.getBankAccount() == null || setting.getBankAccount().isEmpty()) {
                throw new IllegalArgumentException("银行卡号不能为空");
            }
            if (setting.getAccountName() == null || setting.getAccountName().isEmpty()) {
                throw new IllegalArgumentException("户名不能为空");
            }
        }
        
        return depositSettingRepository.save(setting);
    }

    @Transactional
    public DepositSetting updateSetting(Long id, DepositSetting setting) {
        DepositSetting existing = depositSettingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("充值设置不存在"));
        
        if (setting.getNetwork() != null && !setting.getNetwork().isEmpty()) {
            // 检查网络是否与其他记录冲突
            depositSettingRepository.findByNetwork(setting.getNetwork())
                    .ifPresent(s -> {
                        if (!s.getId().equals(id)) {
                            throw new IllegalArgumentException("该网络/币种已被其他设置使用");
                        }
                    });
            existing.setNetwork(setting.getNetwork());
        }
        
        if (setting.getAddress() != null) {
            existing.setAddress(setting.getAddress());
        }
        
        if (setting.getQrCode() != null) {
            existing.setQrCode(setting.getQrCode());
        }
        
        if (setting.getEnabled() != null) {
            existing.setEnabled(setting.getEnabled());
        }
        
        return depositSettingRepository.save(existing);
    }

    @Transactional
    public void deleteSetting(Long id) {
        if (!depositSettingRepository.existsById(id)) {
            throw new IllegalArgumentException("充值设置不存在");
        }
        depositSettingRepository.deleteById(id);
    }
}

