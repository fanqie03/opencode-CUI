CREATE TABLE skill_async_task (
    id           BIGINT PRIMARY KEY COMMENT 'Snowflake ID',
    task_type    VARCHAR(64) NOT NULL COMMENT '任务类型：DELETE_SESSION_MESSAGES',
    payload      JSON NOT NULL COMMENT '任务参数，如 {"sessionId":123,"messageCount":50}',
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/COMPLETED/FAILED',
    retry_count  INT DEFAULT 0 COMMENT '重试次数',
    error_msg    VARCHAR(512) COMMENT '失败原因',
    created_at   DATETIME NOT NULL,
    updated_at   DATETIME
);

CREATE INDEX idx_async_task_status_created
    ON skill_async_task(status, created_at);
