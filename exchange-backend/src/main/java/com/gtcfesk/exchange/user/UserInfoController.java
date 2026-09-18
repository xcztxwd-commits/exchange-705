package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserInfoController {

    private final UserAccountRepository userAccountRepository;
    private final AssetAccountRepository assetAccountRepository;

    @GetMapping("/{userId}/info")
    public ResponseEntity<?> getUserInfo(@PathVariable Long userId) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        List<AssetAccount> assets = assetAccountRepository.findByUserId(userId);
        BigDecimal fund = BigDecimal.ZERO;
        BigDecimal contract = BigDecimal.ZERO;
        BigDecimal option = BigDecimal.ZERO;

        for (AssetAccount a : assets) {
            BigDecimal total = a.getAvailable() == null ? BigDecimal.ZERO : a.getAvailable();
            String coin = a.getCoin() == null ? "" : a.getCoin().toUpperCase();
            switch (coin) {
                case "FUND":
                    fund = total;
                    break;
                case "CONTRACT":
                    contract = total;
                    break;
                case "OPTION":
                    option = total;
                    break;
                default:
                    break;
            }
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", user.getId());
        resp.put("uid", user.getId());
        resp.put("email", user.getEmail());
        resp.put("nickname", user.getNickname());
        resp.put("status", user.getStatus());
        resp.put("fundBalance", fund);
        resp.put("contractBalance", contract);
        resp.put("optionBalance", option);

        return ResponseEntity.ok(resp);
    }

    /**
     * 获取用户详细资产信息（包括余额和冻结金额）
     */
    @GetMapping("/assets")
    public ResponseEntity<?> getUserAssets(org.springframework.security.core.Authentication auth) {
        try {
            if (auth == null) {
                auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            }
            
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录");
                return ResponseEntity.status(401).body(resp);
            }
            
            Long userId = Long.parseLong(auth.getName());
            List<AssetAccount> assets = assetAccountRepository.findByUserId(userId);
            
            BigDecimal fundBalance = BigDecimal.ZERO;
            BigDecimal fundFrozen = BigDecimal.ZERO;
            BigDecimal contractBalance = BigDecimal.ZERO;
            BigDecimal contractFrozen = BigDecimal.ZERO;
            BigDecimal optionBalance = BigDecimal.ZERO;
            BigDecimal optionFrozen = BigDecimal.ZERO;
            
            for (AssetAccount a : assets) {
                BigDecimal available = a.getAvailable() != null ? a.getAvailable() : BigDecimal.ZERO;
                BigDecimal frozen = a.getFrozen() != null ? a.getFrozen() : BigDecimal.ZERO;
                String coin = a.getCoin() == null ? "" : a.getCoin().toUpperCase();
                
                switch (coin) {
                    case "FUND":
                        fundBalance = available;
                        fundFrozen = frozen;
                        break;
                    case "CONTRACT":
                        contractBalance = available;
                        contractFrozen = frozen;
                        break;
                    case "OPTION":
                        optionBalance = available;
                        optionFrozen = frozen;
                        break;
                    default:
                        break;
                }
            }
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("fundBalance", fundBalance);
            resp.put("fundFrozen", fundFrozen);
            resp.put("contractBalance", contractBalance);
            resp.put("contractFrozen", contractFrozen);
            resp.put("optionBalance", optionBalance);
            resp.put("optionFrozen", optionFrozen);
            
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取资产信息失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            e.printStackTrace();
            return ResponseEntity.badRequest().body(resp);
        }
    }
}


