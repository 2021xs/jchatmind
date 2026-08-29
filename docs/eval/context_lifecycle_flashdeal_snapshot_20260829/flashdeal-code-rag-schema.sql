--
-- PostgreSQL database dump
--

\restrict CJDKfVGfJz8yj9bIjM3Pv6Dp6J3zIqpXzRe3zdYWhRlZMST1D8rMdLWukKOqdoj

-- Dumped from database version 16.14 (Debian 16.14-1.pgdg12+1)
-- Dumped by pg_dump version 16.14 (Debian 16.14-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: code_chunk; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.code_chunk (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    repo_id uuid NOT NULL,
    file_id uuid NOT NULL,
    chunk_type character varying(64) NOT NULL,
    symbol_name character varying(255),
    api_path text,
    http_method character varying(32),
    start_line integer,
    end_line integer,
    content text NOT NULL,
    metadata jsonb,
    embedding public.vector(1024),
    created_at timestamp without time zone NOT NULL
);


--
-- Name: code_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.code_file (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    repo_id uuid NOT NULL,
    file_path text NOT NULL,
    file_type character varying(64) NOT NULL,
    package_name character varying(255),
    class_name character varying(255),
    checksum character varying(128),
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);


--
-- Name: code_repository; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.code_repository (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(255) NOT NULL,
    root_path text NOT NULL,
    language character varying(64) DEFAULT 'java'::character varying NOT NULL,
    status character varying(32) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    source_type character varying(32) DEFAULT 'LOCAL'::character varying NOT NULL,
    remote_url text,
    branch character varying(255),
    commit_sha character varying(128)
);


--
-- Name: code_chunk code_chunk_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_chunk
    ADD CONSTRAINT code_chunk_pkey PRIMARY KEY (id);


--
-- Name: code_file code_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_file
    ADD CONSTRAINT code_file_pkey PRIMARY KEY (id);


--
-- Name: code_repository code_repository_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_repository
    ADD CONSTRAINT code_repository_pkey PRIMARY KEY (id);


--
-- Name: idx_code_chunk_api; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_code_chunk_api ON public.code_chunk USING btree (repo_id, api_path);


--
-- Name: idx_code_chunk_repo_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_code_chunk_repo_type ON public.code_chunk USING btree (repo_id, chunk_type);


--
-- Name: idx_code_chunk_symbol; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_code_chunk_symbol ON public.code_chunk USING btree (repo_id, symbol_name);


--
-- Name: idx_code_file_repo_path; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_code_file_repo_path ON public.code_file USING btree (repo_id, file_path);


--
-- Name: idx_code_repository_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_code_repository_created ON public.code_repository USING btree (created_at DESC);


--
-- PostgreSQL database dump complete
--

\unrestrict CJDKfVGfJz8yj9bIjM3Pv6Dp6J3zIqpXzRe3zdYWhRlZMST1D8rMdLWukKOqdoj

