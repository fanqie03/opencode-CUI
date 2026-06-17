package com.opencode.cui.skill.model;

import java.util.Optional;

/**
 * 业务会话 ID 解析结果。
 *
 * <p>格式契约（参考 {@code .trellis/spec/business/protocol-contracts.md}）：
 * <ul>
 *   <li>{@code group_<targetId>_<senderAccount>}</li>
 *   <li>{@code direct_<targetId>_<senderAccount>}</li>
 * </ul>
 * 各段不含下划线；任何其它前缀 / 段数 ≠ 3 视为非法。
 */
public record BusinessSessionId(TargetType targetType, String targetId, String senderAccount) {

    /** 目标类型枚举。wire 字符串通过 {@code name().toLowerCase()} 派生（group / direct）。 */
    public enum TargetType {
        GROUP, DIRECT
    }

    /**
     * 解析 businessSessionId。
     *
     * @param raw 原始字符串（来自 {@code SkillSession.businessSessionId}）
     * @return 解析成功返回 {@link BusinessSessionId}；null / blank / 格式非法均返回 empty
     */
    public static Optional<BusinessSessionId> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        final TargetType type;
        if (raw.startsWith("group_")) {
            type = TargetType.GROUP;
        } else if (raw.startsWith("direct_")) {
            type = TargetType.DIRECT;
        } else {
            return Optional.empty();
        }
        String[] parts = raw.split("_");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String targetId = parts[1];
        String senderAccount = parts[2];
        if (targetId.isBlank() || senderAccount.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new BusinessSessionId(type, targetId, senderAccount));
    }
}
