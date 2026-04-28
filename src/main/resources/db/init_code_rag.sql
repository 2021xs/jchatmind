-- Code RAG minimal schema.
-- The current project DDL style does not use foreign keys. Repository/file/chunk
-- relations are kept consistent by application-level writes.
-- bge-m3 embeddings are stored as vector(1024), matching the embedding model
-- used by the existing RagService.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS code_repository (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(255) NOT NULL,
    root_path text NOT NULL,
    language varchar(64) NOT NULL DEFAULT 'java',
    status varchar(32) NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE TABLE IF NOT EXISTS code_file (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id uuid NOT NULL,
    file_path text NOT NULL,
    file_type varchar(64) NOT NULL,
    package_name varchar(255),
    class_name varchar(255),
    checksum varchar(128),
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE TABLE IF NOT EXISTS code_chunk (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id uuid NOT NULL,
    file_id uuid NOT NULL,
    chunk_type varchar(64) NOT NULL,
    symbol_name varchar(255),
    api_path text,
    http_method varchar(32),
    start_line integer,
    end_line integer,
    content text NOT NULL,
    metadata jsonb,
    embedding vector(1024),
    created_at timestamp NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_code_repository_created
    ON code_repository(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_code_file_repo_path
    ON code_file(repo_id, file_path);

CREATE INDEX IF NOT EXISTS idx_code_chunk_repo_type
    ON code_chunk(repo_id, chunk_type);

CREATE INDEX IF NOT EXISTS idx_code_chunk_symbol
    ON code_chunk(repo_id, symbol_name);

CREATE INDEX IF NOT EXISTS idx_code_chunk_api
    ON code_chunk(repo_id, api_path);

-- First version uses exact vector search:
--   ORDER BY embedding <-> query_vector
-- For larger datasets you may add an ivfflat/hnsw index after importing data
-- and running ANALYZE. ivfflat recall can be affected by its lists parameter,
-- so it is intentionally not created here for empty/small databases.
