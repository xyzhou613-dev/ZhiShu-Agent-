-- 借鉴 4：LLM 主备双端点路由——审计带提供方列（每次尝试一行，'primary'|'backup'；存量行=主路时代）
ALTER TABLE bm_llm_log ADD COLUMN provider VARCHAR(32) AFTER call_type;
UPDATE bm_llm_log SET provider = 'primary' WHERE provider IS NULL;
