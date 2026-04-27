package com.opencode.cui.skill.model;

/**
 * 助手账号 existence 解析结果。
 * 单次调用返回三态 + 成功时附带 ak/ownerWelinkId，避免入站路径为了"先 check 再 resolve"打两次远端。
 *
 * @param status         三态 existence 状态
 * @param ak             助手绑定的应用密钥（仅 EXISTS 时非空）
 * @param ownerWelinkId  助手所有者的 WeLink ID（仅 EXISTS 时非空）
 */
public record ResolveOutcome(ExistenceStatus status, String ak, String ownerWelinkId) {
}
