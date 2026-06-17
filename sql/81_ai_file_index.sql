-- 特性B: AI 文档知识库索引状态(PDF/Word → 向量库 RAG)
-- index_status: NULL/none 不适用 | pending 待索引 | indexing 索引中 | indexed 已索引 | failed 失败
ALTER TABLE dn_ai_file ADD COLUMN index_status VARCHAR(16) DEFAULT NULL COMMENT '文档索引状态(B)';
ALTER TABLE dn_ai_file ADD COLUMN chunk_count INT DEFAULT 0 COMMENT '已入库向量块数(B)';
