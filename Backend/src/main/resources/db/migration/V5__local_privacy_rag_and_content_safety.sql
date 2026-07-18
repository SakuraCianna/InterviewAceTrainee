-- 本迁移建立本地隐私 RAG 边界。用户上传材料不可恢复地清除，公共知识向量只存 Redis 8。

UPDATE ai_jobs
SET input_ref = input_ref
        - 'material_context'
        - 'resume_text'
        - 'job_requirements'
        - 'profile_summary'
        - 'target_school'
        - 'major'
        - 'research_direction';

UPDATE interview_package_stages
SET context_snapshot = context_snapshot
        - 'material_context'
        - 'resume_text'
        - 'job_requirements'
        - 'profile_summary'
        - 'target_school'
        - 'major'
        - 'research_direction';

UPDATE content_safety
SET matched_terms = '[]'::jsonb,
    content_excerpt = NULL;

ALTER TABLE sessions DROP COLUMN material_id;
ALTER TABLE ai_jobs DROP COLUMN material_id;
ALTER TABLE interview_packages DROP COLUMN material_id;
ALTER TABLE interview_packages DROP COLUMN material_snapshot;
DROP TABLE materials;

ALTER TABLE ai_jobs
    ADD CONSTRAINT ck_ai_jobs_input_ref_without_private_material
    CHECK (NOT (input_ref ?| ARRAY[
        'material_context', 'resume_text', 'job_requirements', 'profile_summary',
        'target_school', 'major', 'research_direction'
    ]));

ALTER TABLE content_safety RENAME COLUMN matched_terms TO rule_ids;
ALTER TABLE content_safety DROP COLUMN content_excerpt;
ALTER TABLE content_safety
    ADD COLUMN request_id varchar(128),
    ADD COLUMN job_id uuid REFERENCES ai_jobs(id) ON DELETE SET NULL,
    ADD COLUMN content_digest char(64),
    ADD COLUMN disposition varchar(24) NOT NULL DEFAULT 'observed'
        CHECK (disposition IN ('observed', 'blocked', 'redacted', 'replaced', 'retried'));

CREATE INDEX ix_content_safety_request
    ON content_safety(request_id) WHERE request_id IS NOT NULL;
CREATE INDEX ix_content_safety_job
    ON content_safety(job_id) WHERE job_id IS NOT NULL;

CREATE TABLE knowledge_index_state (
    singleton_id smallint PRIMARY KEY DEFAULT 1 CHECK (singleton_id = 1),
    status varchar(24) NOT NULL
        CHECK (status IN ('DISABLED', 'INDEXING', 'READY', 'DEGRADED', 'FAILED')),
    corpus_version varchar(80) NOT NULL,
    corpus_hash char(64),
    model_id varchar(240) NOT NULL,
    vector_dimensions integer NOT NULL CHECK (vector_dimensions BETWEEN 1 AND 4096),
    document_count integer NOT NULL DEFAULT 0 CHECK (document_count >= 0),
    job_document_count integer NOT NULL DEFAULT 0 CHECK (job_document_count >= 0),
    postgraduate_document_count integer NOT NULL DEFAULT 0 CHECK (postgraduate_document_count >= 0),
    chunk_count integer NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    indexed_chunk_count integer NOT NULL DEFAULT 0 CHECK (indexed_chunk_count >= 0),
    failure_count integer NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    last_error_code varchar(80),
    started_at timestamptz,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (indexed_chunk_count <= chunk_count)
);

INSERT INTO knowledge_index_state(
    singleton_id, status, corpus_version, model_id, vector_dimensions)
VALUES (1, 'DISABLED', 'uninitialized', 'uninitialized', 384);

GRANT SELECT, INSERT, UPDATE ON knowledge_index_state TO mianba_api;
GRANT SELECT ON knowledge_index_state TO mianba_worker;
GRANT INSERT ON content_safety TO mianba_worker;

COMMENT ON TABLE knowledge_index_state IS '公共岗位与考研知识库在 Redis 中的向量索引状态';
COMMENT ON COLUMN knowledge_index_state.singleton_id IS '固定为一的单例主键';
COMMENT ON COLUMN knowledge_index_state.status IS '索引状态：停用、索引中、就绪、降级或失败';
COMMENT ON COLUMN knowledge_index_state.corpus_version IS '公共知识语料版本';
COMMENT ON COLUMN knowledge_index_state.corpus_hash IS '全部公共文档规范化后的 SHA-256 摘要';
COMMENT ON COLUMN knowledge_index_state.model_id IS '本地 ONNX 嵌入模型固定标识';
COMMENT ON COLUMN knowledge_index_state.vector_dimensions IS '向量维度';
COMMENT ON COLUMN knowledge_index_state.document_count IS '发现的公共 Markdown 文档数量';
COMMENT ON COLUMN knowledge_index_state.job_document_count IS '岗位领域公共文档数量';
COMMENT ON COLUMN knowledge_index_state.postgraduate_document_count IS '考研领域公共文档数量';
COMMENT ON COLUMN knowledge_index_state.chunk_count IS '本次语料切片总数';
COMMENT ON COLUMN knowledge_index_state.indexed_chunk_count IS '成功写入 Redis 的切片数量';
COMMENT ON COLUMN knowledge_index_state.failure_count IS '本次索引失败数量';
COMMENT ON COLUMN knowledge_index_state.last_error_code IS '不含用户内容的稳定错误码';
COMMENT ON COLUMN knowledge_index_state.started_at IS '最近一次索引开始时间';
COMMENT ON COLUMN knowledge_index_state.completed_at IS '最近一次索引结束时间';
COMMENT ON COLUMN knowledge_index_state.updated_at IS '索引状态更新时间';

COMMENT ON TABLE content_safety IS 'AI 输入输出风控审计，仅保存规则和不可逆摘要';
COMMENT ON COLUMN content_safety.rule_ids IS '命中的稳定风控规则编号，不保存命中原文';
COMMENT ON COLUMN content_safety.content_digest IS '使用服务端密钥计算的 HMAC-SHA-256 摘要';
COMMENT ON COLUMN content_safety.disposition IS '风控处置结果';
COMMENT ON COLUMN content_safety.request_id IS '关联请求标识';
COMMENT ON COLUMN content_safety.job_id IS '关联 AI 任务标识';

-- 为历史核心业务表补齐中文表注释。
COMMENT ON TABLE users IS '用户账户与训练权益';
COMMENT ON TABLE sessions IS '面试会话状态，不保存用户上传材料';
COMMENT ON TABLE turns IS '面试问题、用户回答与结构化评价';
COMMENT ON TABLE vouchers IS '训练体验券';
COMMENT ON TABLE credit_ledger IS '训练权益变动流水';
COMMENT ON TABLE providers IS 'AI 与语音服务商启停配置';
COMMENT ON TABLE system_configs IS '后台系统配置';
COMMENT ON TABLE ai_jobs IS '异步 AI 任务，只允许业务标识和面试回合引用';
COMMENT ON TABLE outbox_events IS '异步任务可靠发布事件';
COMMENT ON TABLE processed_messages IS 'Worker 消息幂等消费记录';
COMMENT ON TABLE runtime_heartbeats IS '运行角色心跳';
COMMENT ON TABLE reports IS '面试结构化报告';
COMMENT ON TABLE ai_call_logs IS 'AI 调用元数据与成本观测';
COMMENT ON TABLE auth_login IS '认证尝试审计';
COMMENT ON TABLE customer_notes IS '后台客服备注';
COMMENT ON TABLE refund IS '退款与补偿工单';
COMMENT ON TABLE admin_operations IS '后台幂等写操作';
COMMENT ON TABLE admin_audit IS '后台管理操作审计';
COMMENT ON TABLE interview_packages IS '多阶段岗位面试套餐，不保存用户材料快照';
COMMENT ON TABLE interview_package_stages IS '岗位面试套餐阶段计划与进度';
COMMENT ON TABLE report_revisions IS '面试报告不可变修订版本';

-- 所有尚无说明的核心字段获得中文兜底注释，避免数据库管理工具显示空白语义。
DO $comments$
DECLARE
    column_row record;
BEGIN
    FOR column_row IN
        SELECT c.relname AS table_name, a.attname AS column_name
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
        WHERE n.nspname = current_schema()
          AND c.relkind = 'r'
          AND c.relname <> 'flyway_schema_history'
          AND a.attnum > 0
          AND NOT a.attisdropped
          AND col_description(c.oid, a.attnum) IS NULL
    LOOP
        EXECUTE format(
            'COMMENT ON COLUMN %I.%I IS %L',
            column_row.table_name,
            column_row.column_name,
            '业务字段：' || column_row.column_name);
    END LOOP;
END
$comments$;
