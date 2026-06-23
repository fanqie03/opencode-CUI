ALTER TABLE agent_connection
    ADD COLUMN plugin_version VARCHAR(64) COMMENT '插件版本号',
    ADD COLUMN sdk_version VARCHAR(64) COMMENT 'SDK 版本号';
