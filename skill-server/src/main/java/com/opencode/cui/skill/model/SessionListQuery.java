package com.opencode.cui.skill.model;

/**
 * 封装 listSessions 的查询参数，减少方法签名复杂度。
 */
public record SessionListQuery(
        String userId,
        String ak,
        String imGroupId,
        String status,
        int page,
        int size) {
}
