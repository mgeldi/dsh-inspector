-- This file is the DDL, and it is applied by inspector.store.SchemaGate — not by spring.sql.init —
-- because the version gate has to run before anything reads a table: on a file written by an
-- older build the tables exist in their old shape, and Hibernate's validation of the entities
-- against them would fail the boot before the gate could drop and rebuild them.
--
-- The foreign keys below are declarations of what the writers already promise, and they are
-- enforced: the datasource URL carries foreign_keys=on. SQLite applies that pragma per
-- connection and ships it off (sqlite.org/foreignkeys.html §2, "must be enabled separately for
-- each database connection"), so the claim is only as good as every connection's setup —
-- SchemaTest asserts the pragma is on before it asserts a constraint fires, and
-- ApplicationContextTest asserts it of the pool the application actually borrows from.
--
-- A constraint declared here reaches a database file that already exists only through the
-- stale-version reset, which drops the tables so this file recreates them: CREATE TABLE IF NOT
-- EXISTS skips a table that is there, and SQLite has no ALTER TABLE ADD CONSTRAINT. That is why
-- a schema version bump means drop-and-recreate, not empty-and-reuse (DESIGN.md §4.2).

CREATE TABLE IF NOT EXISTS session (
    id                TEXT    NOT NULL,
    source_file       TEXT    NOT NULL,
    project_slug      TEXT    NOT NULL,
    schema            TEXT    NOT NULL,
    started_at        INTEGER NOT NULL,
    ended_at          INTEGER,
    agent_preset      TEXT,
    delegation_depth  INTEGER,
    model             TEXT,
    -- request/context.provider, last seen. The route, which is what tells two models apart
    -- when both are served under one model id.
    provider          TEXT,
    -- 'orchestrator' (delegation depth 0), 'subagent' (depth >= 1), NULL when the header has none
    role              TEXT,
    context_window    INTEGER,
    harness_version   TEXT,
    version_inferred  INTEGER NOT NULL DEFAULT 1,
    indexed_at        INTEGER NOT NULL,
    fatal_turns       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (id, source_file)
);

CREATE TABLE IF NOT EXISTS step (
    session_id     TEXT    NOT NULL,
    source_file    TEXT    NOT NULL,
    turn           INTEGER NOT NULL,
    step           INTEGER NOT NULL,
    started_at     INTEGER NOT NULL,
    ended_at       INTEGER,
    input_tokens   INTEGER,
    output_tokens  INTEGER,
    decode_tps     REAL,
    ttft_ms        INTEGER,
    timing_source  TEXT    NOT NULL,
    PRIMARY KEY (session_id, source_file, turn, step),
    -- The stream a step came from. (id, source_file) is the session primary key because one
    -- session id can appear in both log conventions, so the child key carries both columns.
    FOREIGN KEY (session_id, source_file) REFERENCES session (id, source_file)
);

CREATE TABLE IF NOT EXISTS tool_call (
    session_id   TEXT    NOT NULL,
    source_file  TEXT    NOT NULL,
    turn         INTEGER,
    step         INTEGER,
    seq          INTEGER NOT NULL,
    name         TEXT,
    started_at   INTEGER,
    ended_at     INTEGER,
    duration_ms  INTEGER,
    error_code   TEXT,
    plane        TEXT,
    path_hint    TEXT,
    outcome_only INTEGER NOT NULL DEFAULT 0,
    -- UTC day of started_at, for the daily series, NULL when the call has no start
    day          TEXT,
    -- The event seq inside one stream. Unique by construction — one row per event — and this is
    -- also the index the finding-detail join (session_id, source_file, seq) uses.
    PRIMARY KEY (session_id, source_file, seq),
    FOREIGN KEY (session_id, source_file) REFERENCES session (id, source_file)
);

CREATE TABLE IF NOT EXISTS finding (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id   TEXT    NOT NULL,
    source_file  TEXT    NOT NULL,
    detector     TEXT    NOT NULL,
    plane        TEXT    NOT NULL,
    category     TEXT,
    code         TEXT,
    -- a second constant under code: the provider's specific reason inside a generic one
    -- (INVALID_REQUEST / media_budget_exceeded), or the write form of a shell edit
    detail       TEXT,
    confidence   REAL,
    path_hint    TEXT,
    seq          INTEGER,
    stale_seq    INTEGER,
    cause_seq    INTEGER,
    occurred_at  INTEGER NOT NULL,
    -- UTC day of occurred_at, for the daily series
    day          TEXT    NOT NULL,
    summary      TEXT    NOT NULL,
    FOREIGN KEY (session_id, source_file) REFERENCES session (id, source_file)
);

CREATE TABLE IF NOT EXISTS shell_evidence (
    finding_id       INTEGER NOT NULL,
    seq              INTEGER NOT NULL,
    verb_class       TEXT    NOT NULL,
    path_hint        TEXT,
    excerpt_redacted TEXT,
    PRIMARY KEY (finding_id, seq),
    -- The finding these excerpts were attached to. Its own (finding_id, seq) primary key is
    -- already the index SQLite wants to check this key on deletes, so no extra index here.
    FOREIGN KEY (finding_id) REFERENCES finding (id)
);

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Every index below names the query it serves. An index nothing uses is write cost paid on
-- every re-index plus a standing false claim about what the reads do — the reason
-- idx_tool_call_name_error is gone rather than commented. There is no migration engine
-- (DESIGN.md §4.2) and a reset only empties tables, so a removed index needs its own DROP:
-- without this line an index deleted here would live on in every existing database file.
DROP INDEX IF EXISTS idx_tool_call_name_error;

-- Retired for the same reason, one commit later: idx_finding_occurred was (plane, category,
-- occurred_at) and no read filters findings by category — it is a selected column and a rendered
-- chip, never a WHERE term. With a column that nothing constrains sitting between plane and
-- occurred_at, the plane-filtered findings page could use the prefix and then had to sort, which
-- is the "USE TEMP B-TREE FOR ORDER BY" line in its query plan. See idx_finding_plane_time below.
DROP INDEX IF EXISTS idx_finding_occurred;

-- idx_tool_call_stream (session_id, source_file, seq) is retired: that triple is tool_call's
-- primary key now, and SQLite's automatic index on it serves the finding-detail lookup the old
-- index existed for.
DROP INDEX IF EXISTS idx_tool_call_stream;

-- The (session_id, source_file) join key every read carries (FINDING_JOIN, TOOL_CALL_JOIN),
-- the session_id filter on the findings page, and the per-stream deletes of a re-index.
CREATE INDEX IF NOT EXISTS idx_finding_stream      ON finding (session_id, source_file);

-- The default findings sort: order by f.occurred_at desc, f.id desc. SQLite satisfies the
-- whole clause — tiebreaker included — by scanning this index backwards, because a non-unique
-- index key is internally (occurred_at, rowid) and finding.id is the rowid. Adding id to the
-- index would be decoration: the plan is identical with and without it.
CREATE INDEX IF NOT EXISTS idx_finding_time        ON finding (occurred_at);

-- The plane-filtered findings page: where f.plane = ? order by f.occurred_at desc, f.id desc.
-- Plane first because it is the equality the query carries, occurred_at second because it is what
-- the query then wants in order, and the tiebreaker rides along for the reason stated on
-- idx_finding_time above — a non-unique index key is internally (plane, occurred_at, rowid).
-- The plan agrees: SEARCH f USING INDEX idx_finding_plane_time (plane=?), and no
-- "USE TEMP B-TREE FOR ORDER BY" line, which is what its predecessor left behind.
CREATE INDEX IF NOT EXISTS idx_finding_plane_time  ON finding (plane, occurred_at);

-- The step rows of one stream, and the throughput table's join to session.
CREATE INDEX IF NOT EXISTS idx_step_stream         ON step (session_id, source_file, turn, step);
