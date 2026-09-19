package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.UserBankCard;
import com.gtcfesk.exchange.entity.UserDigitalAddress;
import com.gtcfesk.exchange.repository.UserBankCardRepository;
import com.gtcfesk.exchange.repository.UserDigitalAddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/wallet")
@RequiredArgsConstructor
public class AdminWalletController {

    private final UserBankCardRepository userBankCardRepository;
    private final UserDigitalAddressRepository userDigitalAddressRepository;

    // 获取用户的银行卡列表
    @GetMapping("/{userId}/bank-cards")
    public ResponseEntity<?> getUserBankCards(@PathVariable Long userId) {
        try {
            List<UserBankCard> cards = userBankCardRepository.findByUserId(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", cards);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 添加用户的银行卡
    @PostMapping("/{userId}/bank-cards")
    public ResponseEntity<?> addUserBankCard(@PathVariable Long userId, @RequestBody UserBankCardInput input) {
        try {
            UserBankCard card = new UserBankCard();
            card.setUserId(userId);
            card.setCurrency(input.getCurrency());
            card.setBankName(input.getBankName());
            card.setBankAddress(input.getBankAddress());
            card.setSwift(input.getSwift());
            card.setRecipientName(input.getRecipientName());
            card.setRecipientAccount(input.getRecipientAccount());
            UserBankCard saved = userBankCardRepository.save(card);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", saved);
            resp.put("message", "添加成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "添加失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 更新用户的银行卡
    @PutMapping("/{userId}/bank-cards/{id}")
    public ResponseEntity<?> updateUserBankCard(@PathVariable Long userId, @PathVariable Long id, @RequestBody UserBankCardInput input) {
        try {
            UserBankCard existing = userBankCardRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("银行卡不存在"));
            
            if (!existing.getUserId().equals(userId)) {
                throw new IllegalArgumentException("无权操作");
            }
            
            UserBankCard card = existing;
            card.setCurrency(input.getCurrency());
            card.setBankName(input.getBankName());
            card.setBankAddress(input.getBankAddress());
            card.setSwift(input.getSwift());
            card.setRecipientName(input.getRecipientName());
            card.setRecipientAccount(input.getRecipientAccount());
            UserBankCard saved = userBankCardRepository.save(card);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", saved);
            resp.put("message", "更新成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "更新失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 删除用户的银行卡
    @DeleteMapping("/{userId}/bank-cards/{id}")
    public ResponseEntity<?> deleteUserBankCard(@PathVariable Long userId, @PathVariable Long id) {
        try {
            UserBankCard existing = userBankCardRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("银行卡不存在"));
            
            if (!existing.getUserId().equals(userId)) {
                throw new IllegalArgumentException("无权操作");
            }
            
            userBankCardRepository.deleteById(id);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "删除成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "删除失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 获取用户的数字货币地址列表
    @GetMapping("/{userId}/digital-addresses")
    public ResponseEntity<?> getUserDigitalAddresses(@PathVariable Long userId) {
        try {
            List<UserDigitalAddress> addresses = userDigitalAddressRepository.findByUserId(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", addresses);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 添加用户的数字货币地址
    @PostMapping("/{userId}/digital-addresses")
    public ResponseEntity<?> addUserDigitalAddress(@PathVariable Long userId, @RequestBody UserDigitalAddressInput input) {
        try {
            UserDigitalAddress address = new UserDigitalAddress();
            address.setUserId(userId);
            address.setCurrency(input.getCurrency());
            address.setNetwork(input.getNetwork());
            address.setAddress(input.getAddress());
            UserDigitalAddress saved = userDigitalAddressRepository.save(address);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", saved);
            resp.put("message", "添加成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "添加失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 更新用户的数字货币地址
    @PutMapping("/{userId}/digital-addresses/{id}")
    public ResponseEntity<?> updateUserDigitalAddress(@PathVariable Long userId, @PathVariable Long id, @RequestBody UserDigitalAddressInput input) {
        try {
            UserDigitalAddress existing = userDigitalAddressRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("地址不存在"));
            
            if (!existing.getUserId().equals(userId)) {
                throw new IllegalArgumentException("无权操作");
            }
            
            UserDigitalAddress address = existing;
            address.setCurrency(input.getCurrency());
            address.setNetwork(input.getNetwork());
            address.setAddress(input.getAddress());
            UserDigitalAddress saved = userDigitalAddressRepository.save(address);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", saved);
            resp.put("message", "更新成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "更新失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 删除用户的数字货币地址
    @DeleteMapping("/{userId}/digital-addresses/{id}")
    public ResponseEntity<?> deleteUserDigitalAddress(@PathVariable Long userId, @PathVariable Long id) {
        try {
            UserDigitalAddress existing = userDigitalAddressRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("地址不存在"));
            
            if (!existing.getUserId().equals(userId)) {
                throw new IllegalArgumentException("无权操作");
            }
            
            userDigitalAddressRepository.deleteById(id);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "删除成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "删除失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @lombok.Data
    public static class UserBankCardInput {
        private String currency;
        private String bankName;
        private String bankAddress;
        private String swift;
        private String recipientName;
        private String recipientAccount;
    }

    @lombok.Data
    public static class UserDigitalAddressInput {
        private String currency;
        private String network;
        private String address;
    }
}
