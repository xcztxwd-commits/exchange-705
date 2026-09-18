package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.LoanSetting;
import com.gtcfesk.exchange.repository.LoanSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LoanSettingService {

    private final LoanSettingRepository loanSettingRepository;

    public List<LoanSetting> getAllSettings() {
        return loanSettingRepository.findAll();
    }

    public LoanSetting createSetting(LoanSetting setting) {
        return loanSettingRepository.save(setting);
    }

    @Transactional
    public LoanSetting updateSetting(Long id, LoanSetting setting) {
        LoanSetting existing = loanSettingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("贷款设置不存在"));
        existing.setDays(setting.getDays());
        existing.setDailyRate(setting.getDailyRate());
        existing.setFreeDays(setting.getFreeDays());
        existing.setOverdueRate(setting.getOverdueRate());
        existing.setMinAmount(setting.getMinAmount());
        existing.setMaxAmount(setting.getMaxAmount());
        existing.setEnabled(setting.getEnabled());
        return loanSettingRepository.save(existing);
    }

    @Transactional
    public void deleteSetting(Long id) {
        loanSettingRepository.deleteById(id);
    }
}



