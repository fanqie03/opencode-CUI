package com.opencode.cui.gateway.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * AK/SK credential entity �?maps to the ak_sk_credential table.
 *
 * Used by AkSkAuthService for database-backed AK/SK lookup (REQ-26).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AkSkCredential {

    private Long id;

    /** Access Key �?unique identifier for the credential */
    private String ak;

    /** Secret Key �?used for HMAC-SHA256 signature verification */
    private String sk;

    /** Associated user ID */
    private Long userId;

    /** Human-readable description */
    private String description;

    /** Credential status: ACTIVE or DISABLED */
    @Builder.Default
    private CredentialStatus status = CredentialStatus.ACTIVE;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum CredentialStatus {
        ACTIVE, DISABLED
    }
}
