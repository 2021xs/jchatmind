-- Agent observability tables.
-- The current project mapper style does not define foreign keys in DDL files.
-- These relations are therefore kept consistent by application-level writes.

CREATE TABLE IF NOT EXISTS agent_task (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    user_message_id uuid,
    status varchar(32) NOT NULL,
    goal text,
    started_at timestamp NOT NULL,
    finished_at timestamp,
    error_message text
);

CREATE TABLE IF NOT EXISTS agent_step (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id uuid NOT NULL,
    step_no integer NOT NULL,
    step_type varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    input_summary text,
    output_summary text,
    started_at timestamp NOT NULL,
    finished_at timestamp,
    error_message text
);

CREATE TABLE IF NOT EXISTS tool_call_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id uuid NOT NULL,
    step_id uuid,
    tool_name varchar(128) NOT NULL,
    arguments_json jsonb,
    result_summary text,
    status varchar(32) NOT NULL,
    latency_ms bigint,
    error_message text,
    created_at timestamp NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_task_session_started
    ON agent_task(session_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_step_task_step_no
    ON agent_step(task_id, step_no);

CREATE INDEX IF NOT EXISTS idx_tool_call_log_task_created
    ON tool_call_log(task_id, created_at);
