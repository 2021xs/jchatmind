-- Verify JChatMind database prerequisites and core tables.
-- Usage:
--   psql -U postgres -d jchatmind -f scripts/verify_db.sql

\echo '== pgvector extension =='
SELECT extname
FROM pg_extension
WHERE extname = 'vector';

\echo '== required tables =='
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN (
    'agent',
    'chat_session',
    'chat_message',
    'knowledge_base',
    'document',
    'chunk_bge_m3',
    'agent_task',
    'agent_step',
    'tool_call_log',
    'code_repository',
    'code_file',
    'code_chunk'
  )
ORDER BY table_name;

\echo '== tool_call_log.arguments_json type =='
SELECT table_name, column_name, data_type, udt_name
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'tool_call_log'
  AND column_name = 'arguments_json';

\echo '== code_chunk.embedding type and dimension =='
SELECT c.table_name,
       c.column_name,
       c.data_type,
       c.udt_name,
       format_type(a.atttypid, a.atttypmod) AS formatted_type
FROM information_schema.columns c
JOIN pg_attribute a
  ON a.attrelid = 'code_chunk'::regclass
 AND a.attname = c.column_name
WHERE c.table_schema = 'public'
  AND c.table_name = 'code_chunk'
  AND c.column_name = 'embedding';

\echo '== common indexes =='
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND indexname IN (
    'idx_agent_task_session_started',
    'idx_agent_step_task_step_no',
    'idx_tool_call_log_task_created',
    'idx_code_repository_created',
    'idx_code_file_repo_path',
    'idx_code_chunk_repo_type',
    'idx_code_chunk_symbol',
    'idx_code_chunk_api'
  )
ORDER BY tablename, indexname;

\echo '== recent observability rows =='
SELECT status, count(*) AS task_count
FROM agent_task
GROUP BY status
ORDER BY status;

SELECT tool_name, status, count(*) AS call_count
FROM tool_call_log
GROUP BY tool_name, status
ORDER BY tool_name, status;
