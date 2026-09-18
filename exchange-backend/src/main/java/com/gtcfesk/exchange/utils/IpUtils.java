package com.gtcfesk.exchange.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP工具类
 * 使用在线API精确识别IP地区（精确到省份级别）
 * 使用 uapis.cn API服务（标准查询，支持省份级别查询）
 */
public class IpUtils {

    private static RestTemplate restTemplateInstance;
    
    // IP地区缓存（减少API调用，缓存24小时）
    private static final Map<String, CacheEntry> ipRegionCache = new ConcurrentHashMap<>();
    private static final long CACHE_EXPIRE_TIME = 24 * 60 * 60 * 1000; // 24小时
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // uapis.cn API（精确到市级别，支持中文）
    // API格式（标准查询）：https://uapis.cn/api/v1/network/ipinfo?ip={ip}
    private static final String UAPIS_API_BASE_URL = "https://uapis.cn/api/v1/network/ipinfo";
    
    // 静态初始化 RestTemplate
    static {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = 
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        restTemplateInstance = new RestTemplate(factory);
        restTemplateInstance.getMessageConverters().add(0, 
            new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
    }
    
    /**
     * 缓存条目
     */
    private static class CacheEntry {
        String region;
        long timestamp;
        
        CacheEntry(String region) {
            this.region = region;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRE_TIME;
        }
    }

    /**
     * 获取客户端真实IP地址
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }

        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // 对于多级代理的情况，第一个IP为客户端真实IP，多个IP按照','分割
        if (ip != null && ip.length() > 15) {
            if (ip.indexOf(",") > 0) {
                ip = ip.substring(0, ip.indexOf(",")).trim();
            }
        }

        return ip;
    }

    /**
     * 根据IP地址获取地区信息（使用在线API，精确到市级别）
     * 使用 ip-api.com 免费API服务
     */
    public static String getRegionByIp(String ip) {
        if (ip == null || ip.isEmpty() || "unknown".equals(ip)) {
            return "未知地区";
        }

        // 本地IP
        if (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("localhost") || ip.equals("::1")) {
            return "本地";
        }

        // 内网IP
        if (isInternalIp(ip)) {
            return "内网IP";
        }

        // 先检查缓存
        CacheEntry cacheEntry = ipRegionCache.get(ip);
        if (cacheEntry != null && !cacheEntry.isExpired()) {
            // 如果缓存的是简化的地区信息（如"中国"、"中国大陆"、"海外"），强制重新查询
            String cachedRegion = cacheEntry.region;
            if (cachedRegion != null && (cachedRegion.equals("中国") || cachedRegion.equals("中国大陆") || 
                cachedRegion.equals("海外") || cachedRegion.equals("未知地区"))) {
                // 清除缓存，重新查询
                ipRegionCache.remove(ip);
            } else {
                return cachedRegion;
            }
        }

        // 从缓存中移除过期条目
        if (cacheEntry != null && cacheEntry.isExpired()) {
            ipRegionCache.remove(ip);
        }

        // 使用在线API查询
        String region = queryIpRegionFromApi(ip);
        
        // 缓存结果（即使查询失败也缓存一段时间，避免频繁请求）
        if (region != null && !region.isEmpty() && !region.equals("未知地区")) {
            // 只有详细的地区信息（包含城市）才缓存，简化的地区信息不缓存
            if (!region.equals("中国") && !region.equals("中国大陆") && !region.equals("海外")) {
                ipRegionCache.put(ip, new CacheEntry(region));
            }
            return region;
        } else {
            // 查询失败时，使用备用方案（但不缓存，下次重新尝试API）
            region = getRegionByIpFallback(ip);
            // 备用方案的结果不缓存，下次重新尝试API
            return region;
        }
    }
    
    /**
     * 清除指定IP的缓存（用于强制重新查询）
     */
    public static void clearCache(String ip) {
        if (ip != null) {
            ipRegionCache.remove(ip);
        }
    }
    
    /**
     * 清除所有IP缓存
     */
    public static void clearAllCache() {
        ipRegionCache.clear();
    }

    /**
     * 从在线API查询IP地区信息
     * 使用 uapis.cn API（标准查询，精确到省份级别，支持中文）
     * 文档：https://uapis.cn/api/v1/network/ipinfo
     */
    private static String queryIpRegionFromApi(String ip) {
        try {
            // 调用 uapis.cn API（标准查询，不使用商业数据源）
            // API格式：https://uapis.cn/api/v1/network/ipinfo?ip={ip}
            String url = UAPIS_API_BASE_URL + "?ip=" + java.net.URLEncoder.encode(ip, "UTF-8");
            String response = restTemplateInstance.getForObject(url, String.class);
            
            if (response != null && !response.isEmpty()) {
                System.out.println("[IpUtils] uapis.cn API响应 (" + ip + "): " + response);
                
                // 解析JSON响应
                Map<String, Object> result = objectMapper.readValue(response, 
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                
                // 检查API返回状态或code
                Object codeObj = result.get("code");
                Integer code = null;
                if (codeObj != null) {
                    if (codeObj instanceof Number) {
                        code = ((Number) codeObj).intValue();
                    } else {
                        try {
                            code = Integer.parseInt(codeObj.toString());
                        } catch (NumberFormatException e) {
                            // 如果code不是数字，可能是字符串错误码
                        }
                    }
                }
                
                // uapis.cn 标准查询成功时返回 code: 200
                // 失败时可能有其他code值（400, 404, 500等）或错误消息
                boolean isSuccess = (code != null && code == 200) || 
                                   (code == null && result.containsKey("region") && result.containsKey("ip"));
                
                if (isSuccess) {
                    // uapis.cn返回格式（标准查询）：
                    // region: "中国 广东"（国家 省份）- 标准查询通常只有省份，没有城市
                    // 也可能有 "中国 广东 广州"（国家 省份 城市）- 如果API返回了城市信息
                    String region = getStringValue(result, "region");
                    
                    if (region != null && !region.isEmpty()) {
                        // region格式通常是："国家 省份" 或 "国家 省份 城市"
                        // 处理地址字符串，提取省市信息
                        String formattedRegion = formatUapisRegion(region, null); // 标准查询没有district
                        if (formattedRegion != null && !formattedRegion.isEmpty() && 
                            !formattedRegion.equals("未知地区")) {
                            System.out.println("[IpUtils] 使用region字段 (" + ip + "): " + formattedRegion);
                            return formattedRegion;
                        }
                    }
                    
                    // 如果没有region字段，尝试从其他字段构建
                    // 但根据文档，region应该是必有的字段
                    System.err.println("[IpUtils] uapis.cn API 返回数据不完整 (" + ip + "): 缺少region字段");
                    System.err.println("[IpUtils] 完整响应: " + response);
                } else {
                    // API返回失败
                    String message = getStringValue(result, "message");
                    System.err.println("[IpUtils] uapis.cn API 查询失败 (" + ip + "): code=" + code + ", message=" + message);
                    System.err.println("[IpUtils] 完整响应: " + response);
                }
            }
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // 网络超时或连接失败
            System.err.println("[IpUtils] uapis.cn API 网络请求失败 (" + ip + "): " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("[IpUtils] uapis.cn API 查询异常 (" + ip + "): " + e.getMessage());
            e.printStackTrace();
        }
        
        return null; // 查询失败，返回null，使用备用方案
    }
    
    /**
     * 格式化 uapis.cn API 返回的 region 字段
     * 将 "中国 广东 广州" 或 "中国 广东" 转换为 "广东省 广州市" 或 "广东省" 格式
     * 如果提供district（区），可以显示为 "广东省 广州市 天河区"（仅商业查询提供district）
     * 标准查询通常只有 "国家 省份" 格式，返回 "省份" 或 "省份省"
     */
    private static String formatUapisRegion(String region, String district) {
        if (region == null || region.isEmpty()) {
            return null;
        }
        
        // 去除多余空格
        region = region.trim().replaceAll("\\s+", " ");
        
        // 按空格分割
        String[] parts = region.split("\\s+");
        
        if (parts.length == 0) {
            return null;
        }
        
        // 检查第一个部分是否是"中国"
        int startIndex = 0;
        if (parts[0].equals("中国") || parts[0].equalsIgnoreCase("China") || parts[0].equalsIgnoreCase("CN")) {
            startIndex = 1;
        }
        
        StringBuilder result = new StringBuilder();
        
        // 判断是否是中国地址
        boolean isChina = (startIndex > 0) || (parts.length > 0 && 
            (parts[0].contains("中国") || parts[0].equalsIgnoreCase("China") || parts[0].equalsIgnoreCase("CN")));
        
        // 根据parts数量构建结果
        if (parts.length - startIndex >= 2) {
            // 有省份和城市：显示 "省 市"
            String province = parts[startIndex].trim();
            String city = parts[startIndex + 1].trim();
            
            if (isChina) {
                // 中国地址：格式化省市
                // 确保省份以"省"、"市"、"自治区"、"特别行政区"结尾（如果不是则添加）
                if (!province.endsWith("省") && !province.endsWith("市") && !province.endsWith("自治区") && 
                    !province.endsWith("特别行政区") && !province.endsWith("州") && !province.endsWith("县")) {
                    // 对于直辖市（北京、上海、天津、重庆），不需要加"省"，它们本身就是市
                    if (province.equals("北京") || province.equals("上海") || province.equals("天津") || province.equals("重庆")) {
                        // 直辖市：显示 "北京市" 或 "北京市 区"
                        if (!province.endsWith("市")) {
                            province = province + "市";
                        }
                        result.append(province);
                        // 如果有district（区），添加到后面，例如 "北京市 朝阳区"
                        if (district != null && !district.isEmpty() && !district.trim().isEmpty()) {
                            String districtStr = district.trim();
                            if (!districtStr.endsWith("区") && !districtStr.endsWith("县")) {
                                districtStr = districtStr + "区";
                            }
                            result.append(" ").append(districtStr);
                        }
                        return result.toString().trim();
                    } else {
                        province = province + "省";
                    }
                }
                
                // 确保城市以"市"、"县"、"区"结尾（如果不是则添加）
                if (!city.endsWith("市") && !city.endsWith("县") && !city.endsWith("区") && 
                    !city.endsWith("州") && !city.endsWith("盟")) {
                    city = city + "市";
                }
                
                result.append(province).append(" ").append(city);
                
                // 如果有district（区），添加到后面，例如 "广东省 广州市 天河区"
                if (district != null && !district.isEmpty() && !district.trim().isEmpty()) {
                    String districtStr = district.trim();
                    // 如果district不以"区"、"县"结尾，添加"区"
                    if (!districtStr.endsWith("区") && !districtStr.endsWith("县") && !districtStr.endsWith("市")) {
                        districtStr = districtStr + "区";
                    }
                    result.append(" ").append(districtStr);
                }
            } else {
                // 其他国家：显示 "城市, 省份, 国家" 格式
                result.append(city);
                if (!city.equals(province) && !city.contains(province)) {
                    result.append(", ").append(province);
                }
                if (startIndex > 0 && parts[0] != null) {
                    result.append(", ").append(parts[0]);
                }
            }
        } else if (parts.length - startIndex == 1) {
            // 只有省份或城市：显示省份或城市
            String location = parts[startIndex].trim();
            
            if (isChina) {
                // 中国地址：格式化省份
                // 确保省份以"省"、"市"、"自治区"结尾（如果不是则添加）
                if (!location.endsWith("省") && !location.endsWith("市") && !location.endsWith("自治区") && 
                    !location.endsWith("特别行政区") && !location.endsWith("州") && !location.endsWith("县")) {
                    // 对于直辖市，不需要加"省"
                    if (location.equals("北京") || location.equals("上海") || location.equals("天津") || location.equals("重庆")) {
                        location = location + "市";
                    } else {
                        location = location + "省";
                    }
                }
                result.append(location);
            } else {
                // 其他国家：直接显示
                result.append(location);
                if (startIndex > 0 && parts[0] != null) {
                    result.append(", ").append(parts[0]);
                }
            }
        } else {
            // 只有国家，返回null让后续逻辑处理
            return null;
        }
        
        return result.toString().trim();
    }
    
    /**
     * 安全获取Map中的字符串值
     */
    private static String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        // 处理数字类型（如region可能是数字代码）
        if (value instanceof Number) {
            return null; // 忽略数字类型的region代码
        }
        String str = value.toString().trim();
        // 过滤掉无效值
        if (str.isEmpty() || str.equals("null") || str.equals("N/A") || str.equals("")) {
            return null;
        }
        return str;
    }

    /**
     * 备用方案：简化版IP地区识别（当API不可用时使用）
     */
    private static String getRegionByIpFallback(String ip) {
        try {
            // 简单的IP段判断（仅作为备用）
            // 注意：这种方法不够准确，只应在API不可用时使用
            if (ip.startsWith("223.") || ip.startsWith("218.") || ip.startsWith("219.") || 
                ip.startsWith("220.") || ip.startsWith("221.") || ip.startsWith("222.") ||
                (ip.startsWith("1.") && !ip.startsWith("10.")) ||
                (ip.startsWith("14.")) || (ip.startsWith("27.")) ||
                (ip.startsWith("36.")) || (ip.startsWith("39.")) ||
                (ip.startsWith("42.")) || (ip.startsWith("49.")) ||
                (ip.startsWith("58.")) || (ip.startsWith("59.")) ||
                (ip.startsWith("60.")) || (ip.startsWith("61.")) ||
                (ip.startsWith("101.")) || (ip.startsWith("103.")) ||
                (ip.startsWith("106.")) || (ip.startsWith("110.")) ||
                (ip.startsWith("111.")) || (ip.startsWith("112.")) ||
                (ip.startsWith("113.")) || (ip.startsWith("114.")) ||
                (ip.startsWith("115.")) || (ip.startsWith("116.")) ||
                (ip.startsWith("117.")) || (ip.startsWith("118.")) ||
                (ip.startsWith("119.")) || (ip.startsWith("120.")) ||
                (ip.startsWith("121.")) || (ip.startsWith("122.")) ||
                (ip.startsWith("123.")) || (ip.startsWith("124.")) ||
                (ip.startsWith("125.")) || (ip.startsWith("126.")) ||
                (ip.startsWith("171.")) || (ip.startsWith("175.")) ||
                (ip.startsWith("180.")) || (ip.startsWith("182.")) ||
                (ip.startsWith("183.")) || (ip.startsWith("202.")) ||
                (ip.startsWith("203.")) || (ip.startsWith("210.")) ||
                (ip.startsWith("211.")) || (ip.startsWith("218."))) {
                return "中国";
            }
        } catch (Exception e) {
            // 解析失败
        }

        return "海外";
    }

    /**
     * 判断是否为内网IP
     */
    private static boolean isInternalIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
            return true;
        }
        
        if (ip.startsWith("172.")) {
            try {
                String[] parts = ip.split("\\.");
                if (parts.length >= 2) {
                    int secondPart = Integer.parseInt(parts[1]);
                    if (secondPart >= 16 && secondPart <= 31) {
                        return true;
                    }
                }
            } catch (Exception e) {
                // 忽略异常
            }
        }
        
        // IPv6 内网地址
        if (ip.startsWith("fe80:") || ip.startsWith("fc00:") || ip.startsWith("fd00:")) {
            return true;
        }
        
        return false;
    }
}

