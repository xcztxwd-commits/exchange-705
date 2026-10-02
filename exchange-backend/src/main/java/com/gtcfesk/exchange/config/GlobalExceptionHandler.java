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
    @ExceptionHandler(com.gtcfesk.exchange.common.KycRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleKycRequired(com.gtcfesk.exchange.common.KycRequiredException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("errorCode", "KYC_REQUIRED");
        result.put("kycStatus", e.getKycStatus());
        result.put("message", e.getMessage());
        result.put("exitHandling", "KYC开启时普通用户存量手动退出仍拦截。请联系客服提交订单号；管理员或总控通过受控退出核实原因并处理。自动到期结算不停止。处理时限按客服服务约定，未配置时不承诺固定时限。");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).header("Cache-Control", "no-store").body(result);
    }

    @ExceptionHandler(com.gtcfesk.exchange.market.BalancedControlPlan.Failure.class)
    public ResponseEntity<Map<String, Object>> handleControlPlan(com.gtcfesk.exchange.market.BalancedControlPlan.Failure e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false); result.put("errorCode", e.code); result.put("message", e.getMessage());
        return ResponseEntity.status("PLAN_CORRUPTED".equals(e.code) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST).body(result);
    }
    @ExceptionHandler(com.gtcfesk.exchange.security.SecurityFailure.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(com.gtcfesk.exchange.security.SecurityFailure e) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setCacheControl("no-store");
        if (e.retryAfter > 0) headers.set("Retry-After", Long.toString(e.retryAfter));
        return new ResponseEntity<>(com.gtcfesk.exchange.security.RegistrationSecurityFilter.body(e), headers, HttpStatus.valueOf(e.status));
    }


    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", com.gtcfesk.exchange.common.SafeErrors.message(e));
        result.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
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

    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.validation.BindException.class, javax.validation.ConstraintViolationException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleInput(Exception e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "请求参数无效或不完整");
        return ResponseEntity.badRequest().body(result);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(Exception e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "无权执行此操作");
        return ResponseEntity.status(403).body(result);
    }

    @ExceptionHandler({org.springframework.dao.OptimisticLockingFailureException.class,
            org.springframework.dao.PessimisticLockingFailureException.class, javax.persistence.OptimisticLockException.class})
    public ResponseEntity<Map<String, Object>> handleConflict(Exception e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "数据已变更，请刷新后重试");
        return ResponseEntity.status(409).body(result);
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> handleStatus(org.springframework.web.server.ResponseStatusException e) {
        Map<String,Object> result=new HashMap<>(); result.put("success",false); result.put("message",e.getReason());
        return ResponseEntity.status(e.getStatus()).body(result);
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String,Object>> handleMethod(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        Map<String,Object> result=new HashMap<>();result.put("success",false);result.put("message","请求方法不支持");
        org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();headers.setCacheControl("no-store");
        if(e.getSupportedHttpMethods()!=null)headers.setAllow(e.getSupportedHttpMethods());
        return new ResponseEntity<>(result,headers,HttpStatus.METHOD_NOT_ALLOWED);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", "Internal Server Error");
        result.put("message", "Unable to confirm the result. Check the relevant history or status before submitting again.");
        System.err.println("未处理的异常: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }
}





