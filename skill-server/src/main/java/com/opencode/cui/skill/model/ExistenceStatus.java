package com.opencode.cui.skill.model;

/**
 * 助手账号 existence 状态三态。
 *
 * <ul>
 *   <li>{@link #EXISTS} — IM 平台确认助手存在且上游数据齐全（ak + ownerWelinkId）</li>
 *   <li>{@link #NOT_EXISTS} — IM 平台明确告知助手不存在（已删除）</li>
 *   <li>{@link #UNKNOWN} — 无法判定：上游数据残缺 / 接口不可用 / 超时 / 异常</li>
 * </ul>
 */
public enum ExistenceStatus {
    EXISTS,
    NOT_EXISTS,
    UNKNOWN
}
