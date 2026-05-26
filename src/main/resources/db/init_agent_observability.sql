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
    finish_reason varchar(64),
    model_name varchar(128),
    max_steps integer,
    actual_steps integer,
    tool_call_count integer,
    latency_ms bigint,
    trace_id varchar(128),
    heartbeat_at timestamp,
    started_at timestamp NOT NULL,
    finished_at timestamp,
    updated_at timestamp,
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
    latency_ms bigint,
    model_name varchar(128),
    llm_latency_ms bigint,
    input_tokens integer,
    output_tokens integer,
    finish_reason varchar(64),
    started_at timestamp NOT NULL,
    finished_at timestamp,
    updated_at timestamp,
    error_message text
);

CREATE TABLE IF NOT EXISTS tool_call_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id uuid NOT NULL,
    step_id uuid,
    tool_name varchar(128) NOT NULL,
    actual_tool_name varchar(128),
    tool_call_id varchar(128),
    arguments_json jsonb,
    result_summary text,
    status varchar(32) NOT NULL,
    latency_ms bigint,
    error_message text,
    error_type varchar(64),
    blocked_by_policy boolean,
    argument_truncated boolean,
    result_truncated boolean,
    retry_count integer,
    started_at timestamp,
    finished_at timestamp,
    created_at timestamp NOT NULL,
    updated_at timestamp
);

CREATE INDEX IF NOT EXISTS idx_agent_task_session_started
    ON agent_task(session_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_step_task_step_no
    ON agent_step(task_id, step_no);

CREATE INDEX IF NOT EXISTS idx_tool_call_log_task_created
    ON tool_call_log(task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_agent_task_status_heartbeat
    ON agent_task(status, heartbeat_at);

CREATE INDEX IF NOT EXISTS idx_agent_step_task_status
    ON agent_step(task_id, status);

CREATE INDEX IF NOT EXISTS idx_tool_call_log_task_status
    ON tool_call_log(task_id, status);
