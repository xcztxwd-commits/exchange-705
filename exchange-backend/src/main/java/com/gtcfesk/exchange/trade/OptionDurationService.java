package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.entity.OptionDuration;
import com.gtcfesk.exchange.repository.OptionDurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OptionDurationService {

    private final OptionDurationRepository optionDurationRepository;

    /**
     * 获取启用的期限选项列表
     */
    public List<OptionDuration> getEnabledDurations() {
        return optionDurationRepository.findByEnabledTrueOrderBySortOrderAsc();
    }

    /**
     * 获取所有期限选项列表
     */
    public List<OptionDuration> getAllDurations() {
        return optionDurationRepository.findAllByOrderBySortOrderAsc();
    }
}



