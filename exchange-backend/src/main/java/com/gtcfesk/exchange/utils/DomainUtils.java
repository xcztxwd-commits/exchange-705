package com.gtcfesk.exchange.utils;

import javax.servlet.http.HttpServletRequest;
import java.net.URL;

/**
 * 域名工具类
 */
public class DomainUtils {

    /**
     * 从HTTP请求中提取登录域名
     * 优先从Origin头获取，如果没有则从Referer头获取
     */
    public static String getLoginDomain(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String domain = null;

        // 优先从Origin头获取
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty()) {
            domain = extractDomainFromUrl(origin);
            if (domain != null && !domain.isEmpty()) {
                return domain;
            }
        }

        // 如果Origin没有，从Referer头获取
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isEmpty()) {
            domain = extractDomainFromUrl(referer);
            if (domain != null && !domain.isEmpty()) {
                return domain;
            }
        }

        // 如果都没有，从Host头获取
        String host = request.getHeader("Host");
        if (host != null && !host.isEmpty()) {
            // 移除端口号
            if (host.contains(":")) {
                host = host.substring(0, host.indexOf(":"));
            }
            return host;
        }

        return null;
    }

    /**
     * 从URL中提取域名
     */
    private static String extractDomainFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        try {
            // 如果URL不包含协议，添加http://前缀
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }

            URL urlObj = new URL(url);
            String host = urlObj.getHost();
            
            // 移除www.前缀（可选）
            if (host != null && host.startsWith("www.")) {
                host = host.substring(4);
            }
            
            return host;
        } catch (Exception e) {
            // URL解析失败，尝试简单提取
            try {
                // 移除协议前缀
                if (url.startsWith("http://")) {
                    url = url.substring(7);
                } else if (url.startsWith("https://")) {
                    url = url.substring(8);
                }
                
                // 提取域名部分（到第一个/或:之前）
                int slashIndex = url.indexOf("/");
                int colonIndex = url.indexOf(":");
                
                int endIndex = url.length();
                if (slashIndex > 0) {
                    endIndex = slashIndex;
                }
                if (colonIndex > 0 && colonIndex < endIndex) {
                    endIndex = colonIndex;
                }
                
                String host = url.substring(0, endIndex);
                
                // 移除www.前缀
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
                
                return host;
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * 检查域名是否在白名单中
     */
    public static boolean isDomainInWhitelist(String domain, String whitelistStr) {
        if (domain == null || domain.isEmpty() || whitelistStr == null || whitelistStr.isEmpty()) {
            return false;
        }

        // 白名单格式：每行一个域名，用换行符或逗号分隔
        String[] domains = whitelistStr.split("[,\\n\\r]+");
        
        for (String whitelistDomain : domains) {
            whitelistDomain = whitelistDomain.trim();
            if (whitelistDomain.isEmpty()) {
                continue;
            }
            
            // 精确匹配
            if (domain.equalsIgnoreCase(whitelistDomain)) {
                return true;
            }
            
            // 支持通配符匹配（如 *.example.com）
            if (whitelistDomain.startsWith("*.")) {
                String suffix = whitelistDomain.substring(2);
                if (domain.endsWith("." + suffix) || domain.equals(suffix)) {
                    return true;
                }
            }
        }
        
        return false;
    }
}




