package com.gtcfesk.exchange.config;

import com.gtcfesk.exchange.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", e.getMessage());
        result.put("message", e.getMessage());
        result.put("code", e.getCode());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public ResponseEntity<Map<String, Object>> handleMultipartException(Exception e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", "文件上传大小超限");
        
        String message = "文件大小不能超过10MB";
        if (e.getMessage() != null && e.getMessage().contains("exceeds")) {
            message = "文件大小超出限制，请上传小于10MB的图片";
        }
        
        result.put("message", message);
        System.err.println("文件上传异常: " + e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", "Internal Server Error");
        result.put("message", e.getMessage());
        System.err.println("未处理的异常: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }
}





