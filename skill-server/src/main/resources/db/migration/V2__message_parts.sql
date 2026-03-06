-- =============================================
-- V2: Add message_part table + extend skill_message
-- =============================================

-- 1. Extend skill_message: add message_id (OpenCode), finished flag, token/cost stats
ALTER TABLE skill_message
  ADD COLUMN message_id VARCHAR(128) NULL AFTER id,
  ADD COLUMN finished   TINYINT(1) NOT NULL DEFAULT 0 AFTER meta,
  ADD COLUMN tokens_in  INT NULL AFTER finished,
  ADD COLUMN tokens_out INT NULL AFTER tokens_in,
  ADD COLUMN cost       DECIMAL(10,6) NULL AFTER tokens_out;

ALTER TABLE skill_message
  ADD INDEX idx_message_id (message_id);

-- 2. New table: skill_message_part
CREATE TABLE skill_message_part (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id     BIGINT NOT NULL COMMENT 'FK to skill_message.id',
  session_id     BIGINT NOT NULL,
  part_id        VARCHAR(128) NOT NULL COMMENT 'OpenCode part ID',
  seq            INT NOT NULL DEFAULT 0 COMMENT 'Order within message',
  part_type      VARCHAR(30) NOT NULL COMMENT 'text / reasoning / tool / file / step-start / step-finish',

  -- Content (text / reasoning)
  content        MEDIUMTEXT NULL,

  -- Tool fields
  tool_name      VARCHAR(100) NULL,
  tool_call_id   VARCHAR(128) NULL,
  tool_status    VARCHAR(20) NULL COMMENT 'pending / running / completed / error',
  tool_input     JSON NULL,
  tool_output    MEDIUMTEXT NULL,
  tool_error     TEXT NULL,
  tool_title     VARCHAR(500) NULL,

  -- File fields
  file_name      VARCHAR(500) NULL,
  file_url       TEXT NULL,
  file_mime      VARCHAR(100) NULL,

  -- Step finish fields
  tokens_in      INT NULL,
  tokens_out     INT NULL,
  cost           DECIMAL(10,6) NULL,
  finish_reason  VARCHAR(50) NULL,

  created_at     DATETIME NOT NULL,
  updated_at     DATETIME NOT NULL,

  INDEX idx_message (message_id),
  INDEX idx_session (session_id),
  UNIQUE INDEX idx_part_id (session_id, part_id)
);
