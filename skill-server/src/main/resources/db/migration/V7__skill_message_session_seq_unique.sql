ALTER TABLE skill_message
  DROP INDEX idx_session_seq,
  ADD CONSTRAINT uk_skill_message_session_seq UNIQUE (session_id, seq);
