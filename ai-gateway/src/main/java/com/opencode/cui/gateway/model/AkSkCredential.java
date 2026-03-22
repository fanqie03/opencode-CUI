package com.opencode.cui.gateway.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * AK/SK 凭证实体。
 * 对应数据库 ak_sk_credential 表，用于 Agent WebSocket 握手时的鉴权校验。
 *
 * <p>
 * AkSkAuthService 通过 AK 查询此表获取 SK，
 * 然后使用 HMAC-SHA256 算法验证客户端签名。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AkSkCredential {

    /** 数据库主键 */
    private Long id;

    /** 应用密钥（Access Key）— 凭证的唯一标识 */
    private String ak;

    /** 密钥（Secret Key）— 用于 HMAC-SHA256 签名验证 */
    private String sk;

    /** 关联用户 ID */
    private String userId;

    /** 凭证描述（便于管理识别） */
    private String description;

    /** 凭证状态：ACTIVE（启用）或 DISABLED（禁用） */
    @Builder.Default
    private CredentialStatus status = CredentialStatus.ACTIVE;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 凭证状态枚举。
     */
    public enum CredentialStatus {
        /** 启用 */
        ACTIVE,
        /** 禁用 */
        DISABLED
    }
}
