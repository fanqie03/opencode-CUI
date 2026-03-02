-- PCAgent connection registry
CREATE TABLE agent_connection (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT NOT NULL,
  ak_id           VARCHAR(64) NOT NULL,
  device_name     VARCHAR(100),
  os              VARCHAR(50),
  tool_type       VARCHAR(50) NOT NULL DEFAULT 'OPENCODE',
  tool_version    VARCHAR(50),
  status          ENUM('ONLINE','OFFLINE') DEFAULT 'OFFLINE',
  last_seen_at    DATETIME,
  created_at      DATETIME NOT NULL,
  INDEX idx_user (user_id),
  INDEX idx_ak (ak_id),
  INDEX idx_status (status)
);
