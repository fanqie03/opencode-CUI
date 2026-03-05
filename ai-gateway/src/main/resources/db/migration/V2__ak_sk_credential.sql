-- AK/SK credential store (REQ-26)
-- Replaces hardcoded test credentials in AkSkAuthService

CREATE TABLE ak_sk_credential (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  ak          VARCHAR(64)  NOT NULL UNIQUE COMMENT 'Access Key',
  sk          VARCHAR(128) NOT NULL        COMMENT 'Secret Key',
  user_id     BIGINT       NOT NULL        COMMENT 'Associated user ID',
  description VARCHAR(200)                 COMMENT 'Credential description',
  status      ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at  DATETIME     NOT NULL,
  updated_at  DATETIME,
  INDEX idx_ak (ak),
  INDEX idx_user_id (user_id),
  INDEX idx_status (status)
);

-- Insert test credential (same as previously hardcoded values)
INSERT INTO ak_sk_credential (ak, sk, user_id, description, status, created_at)
VALUES ('test-ak-001', 'test-sk-secret-001', 1, 'Development test credential', 'ACTIVE', NOW());
