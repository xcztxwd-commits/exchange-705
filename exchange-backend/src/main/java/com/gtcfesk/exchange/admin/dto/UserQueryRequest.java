package com.gtcfesk.exchange.admin.dto;

import lombok.Data;

@Data
public class UserQueryRequest {
    private Long userId; // 用户ID
    private String keyword;
    private String status;
    private String userType; // normal, agent
    private Integer page = 0;
    private Integer size = 20;
    private Long agentId; // 代理ID（用于代理只查看下级用户）
    private Long filterAgentId; // 管理员筛选代理ID（筛选特定代理的下级用户）
}







