package com.gtcfesk.exchange.market;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 行情配置控制器（已废弃）
 *
 * 说明：
 * - 早期用于前端探测阿里云/Alltick 行情配置是否存在
 * - 现行版本行情全部由后端统一走 Forex HTTP 接口，无需再暴露任何配置
 *
 * 为避免产生误用，该控制器保留空壳实现，仅返回固定说明。
 */
@RestController
@RequestMapping("/api/market/config")
public class MarketConfigController {

    @GetMapping("/aliyun")
    public ResponseEntity<?> getAliyunConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("configured", "false");
        config.put("message", "阿里云行情已下线，当前系统使用Forex行情接口，无需配置APPCODE");
        return ResponseEntity.ok(config);
    }

    @Deprecated
    @GetMapping("/alltick")
    public ResponseEntity<?> getAlltickConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("apiKey", "");
        config.put("message", "Alltick行情已下线，接口仅为兼容保留，不再使用");
        return ResponseEntity.ok(config);
    }
}

