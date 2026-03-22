-- 会话级路由表：精确记录每个会话应该路由到哪个 Source 实例
-- 用于 1 AK : N Source 并发场景下的精确上行路由
CREATE TABLE session_route (
  id                BIGINT       NOT NULL PRIMARY KEY COMMENT 'Snowflake ID',
  ak                VARCHAR(64)  NOT NULL             COMMENT 'Agent Access Key',
  welink_session_id BIGINT       NOT NULL             COMMENT 'skill_sessions.id',
  tool_session_id   VARCHAR(128) NULL                 COMMENT 'OpenCode 侧会话 ID（session_created 后回填）',
  source_type       VARCHAR(32)  NOT NULL             COMMENT '来源服务类型: skill-server, bot-platform 等',
  source_instance   VARCHAR(128) NOT NULL             COMMENT '来源实例 ID: ss-az1-pod-2',
  user_id           VARCHAR(128) NOT NULL             COMMENT '会话所有者（AK 拥有者）',
  status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / CLOSED',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  UNIQUE INDEX uk_welink_source (welink_session_id, source_type),
  INDEX idx_tool_session (tool_session_id),
  INDEX idx_ak_status (ak, status),
  INDEX idx_source_instance (source_instance, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话级路由表';
