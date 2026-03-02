-- Skill Definition (admin-configured, only OpenCode for MVP)
CREATE TABLE skill_definition (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  skill_code  VARCHAR(50) NOT NULL UNIQUE,
  skill_name  VARCHAR(100) NOT NULL,
  tool_type   VARCHAR(50) NOT NULL DEFAULT 'OPENCODE',
  description VARCHAR(500),
  icon_url    VARCHAR(200),
  status      ENUM('ACTIVE','DISABLED') DEFAULT 'ACTIVE',
  sort_order  INT DEFAULT 0,
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL
);

-- Skill Session
CREATE TABLE skill_session (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id             BIGINT NOT NULL,
  skill_definition_id BIGINT NOT NULL,
  agent_id            BIGINT,
  tool_session_id     VARCHAR(128),
  title               VARCHAR(200),
  status              ENUM('ACTIVE','IDLE','CLOSED') DEFAULT 'ACTIVE',
  im_chat_id          VARCHAR(128),
  created_at          DATETIME NOT NULL,
  last_active_at      DATETIME NOT NULL,
  INDEX idx_user_active (user_id, status),
  INDEX idx_agent (agent_id)
);

-- Skill Message
CREATE TABLE skill_message (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id   BIGINT NOT NULL,
  seq          INT NOT NULL,
  role         ENUM('USER','ASSISTANT','SYSTEM','TOOL') NOT NULL,
  content      MEDIUMTEXT NOT NULL,
  content_type ENUM('MARKDOWN','CODE','PLAIN') DEFAULT 'MARKDOWN',
  created_at   DATETIME NOT NULL,
  meta         JSON,
  INDEX idx_session_seq (session_id, seq)
);

-- Seed data: OpenCode skill definition
INSERT INTO skill_definition (skill_code, skill_name, tool_type, description, icon_url, status, sort_order, created_at, updated_at)
VALUES ('opencode', 'OpenCode AI', 'OPENCODE', 'AI 编码助手 - 代码生成、分析、重构', '/icons/opencode.svg', 'ACTIVE', 1, NOW(), NOW());
