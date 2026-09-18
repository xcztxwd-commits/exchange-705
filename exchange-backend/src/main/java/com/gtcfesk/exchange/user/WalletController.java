package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.UserBankCard;
import com.gtcfesk.exchange.entity.UserDigitalAddress;
import com.gtcfesk.exchange.repository.UserBankCardRepository;
import com.gtcfesk.exchange.repository.UserDigitalAddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final UserBankCardRepository userBankCardRepository;
    private final UserDigitalAddressRepository userDigitalAddressRepository;

    // 获取用户绑定的银行卡列表
    @GetMapping("/bank-cards")
    public ResponseEntity<?> getBankCards(Authentication auth) {
        try {
            Long userId = Long.parseLong(auth.getName());
            List<UserBankCard> cards = userBankCardRepository.findByUserId(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", cards);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 添加银行卡
    @PostMapping("/bank-cards")
    public ResponseEntity<?> addBankCard(Authentication auth, @RequestBody UserBankCard card) {
        try {
            Long userId = Long.parseLong(auth.getName());
            card.setUserId(userId);
            
            UserBankCard saved = userBankCardRepository.save(card);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", saved);
            resp.put("message", "添加成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "添加失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 更新银行卡
    @PutMapping("/bank-cards/{id}")
    public ResponseEntity<?> updateBankCard(Authentication auth, @PathVariable Long id, @RequestBody UserBankCard card) {
        try {
            Long userId = Long.parseLong(auth.getName());
            UserBankCard existing = userBankCardRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("银行卡不存在"));
            
            if (!existing.getUserId().equals(userId)) {
                throw new IllegalArgumentException("无权操作");
            }
            
            card.setId(id);
            card.setUserId(userId);
            UserBankCard saved = userBankCardRepository.save(card);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", saved);
            resp.put("message", "更新成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "更新失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 删除银行卡
    @DeleteMapping("/bank-cards/{id}")
    public ResponseEntity<?> deleteBankCard(Authentication auth, @PathVariable Long id) {
        try {
            Long userId = Long.parseLong(auth.getName());
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
            resp.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 获取用户绑定的数字货币地址列表
    @GetMapping("/digital-addresses")
    public ResponseEntity<?> getDigitalAddresses(Authentication auth) {
        try {
            Long userId = Long.parseLong(auth.getName());
            List<UserDigitalAddress> addresses = userDigitalAddressRepository.findByUserId(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", addresses);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 添加数字货币地址
    @PostMapping("/digital-addresses")
    public ResponseEntity<?> addDigitalAddress(Authentication auth, @RequestBody UserDigitalAddress address) {
        try {
            Long userId = Long.parseLong(auth.getName());
            address.setUserId(userId);
            
            UserDigitalAddress saved = userDigitalAddressRepository.save(address);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", saved);
            resp.put("message", "添加成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "添加失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 更新数字货币地址
    @PutMapping("/digital-addresses/{id}")
    public ResponseEntity<?> updateDigitalAddress(Authentication auth, @PathVariable Long id, @RequestBody UserDigitalAddress address) {
        try {
            Long userId = Long.parseLong(auth.getName());
            UserDigitalAddress existing = userDigitalAddressRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("地址不存在"));
            
            if (!existing.getUserId().equals(userId)) {
                throw new IllegalArgumentException("无权操作");
            }
            
            address.setId(id);
            address.setUserId(userId);
            UserDigitalAddress saved = userDigitalAddressRepository.save(address);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", saved);
            resp.put("message", "更新成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "更新失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // 删除数字货币地址
    @DeleteMapping("/digital-addresses/{id}")
    public ResponseEntity<?> deleteDigitalAddress(Authentication auth, @PathVariable Long id) {
        try {
            Long userId = Long.parseLong(auth.getName());
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
            resp.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
}



