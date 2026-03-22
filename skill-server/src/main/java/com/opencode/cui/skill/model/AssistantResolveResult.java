package com.opencode.cui.skill.model;

/**
 * 助手账号解析结果。
 * 用于从 IM 入站消息中的助手账号反查绑定的 AK 和所有者信息。
 *
 * @param ak            助手绑定的应用密钥
 * @param ownerWelinkId 助手所有者的 WeLink ID（在 Gateway 交互中作为 userId 使用）
 */
public record AssistantResolveResult(String ak, String ownerWelinkId) {
}
