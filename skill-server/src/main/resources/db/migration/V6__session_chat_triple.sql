ALTER TABLE skill_session
  ADD COLUMN business_session_domain VARCHAR(32) NOT NULL DEFAULT 'miniapp' COMMENT '来源场景（miniapp/im/meeting/doc）' AFTER status,
  ADD COLUMN business_session_type VARCHAR(32) NULL COMMENT '会话类型（group/direct）' AFTER business_session_domain,
  ADD COLUMN business_session_id VARCHAR(128) NULL COMMENT '平台定义的会话标识' AFTER business_session_type,
  ADD COLUMN assistant_account VARCHAR(128) NULL COMMENT '数字分身的平台账号标识' AFTER business_session_id;

UPDATE skill_session
SET business_session_id = im_group_id
WHERE im_group_id IS NOT NULL
  AND business_session_id IS NULL;

ALTER TABLE skill_session
  DROP COLUMN im_group_id;

ALTER TABLE skill_session
  ADD INDEX idx_biz_session_lookup (business_session_domain, business_session_type, business_session_id, ak);

ALTER TABLE skill_definition
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（应用层枚举：ACTIVE/DISABLED）';

ALTER TABLE skill_session
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（应用层枚举：ACTIVE/IDLE/CLOSED）';

ALTER TABLE skill_message
  MODIFY COLUMN role VARCHAR(16) NOT NULL COMMENT '角色（应用层枚举：USER/ASSISTANT/SYSTEM/TOOL）',
  MODIFY COLUMN content_type VARCHAR(16) NOT NULL DEFAULT 'MARKDOWN' COMMENT '内容类型（应用层枚举：MARKDOWN/CODE/PLAIN/IMAGE）';
