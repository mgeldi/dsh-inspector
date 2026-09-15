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
    PRIMARY KEY (session_id, source_file, turn, step)
);

CREATE TABLE IF NOT EXISTS tool_call (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
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
    outcome_only INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS finding (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id   TEXT    NOT NULL,
    source_file  TEXT    NOT NULL,
    detector     TEXT    NOT NULL,
    plane        TEXT    NOT NULL,
    category     TEXT,
    code         TEXT,
    confidence   REAL,
    path_hint    TEXT,
    seq          INTEGER,
    stale_seq    INTEGER,
    cause_seq    INTEGER,
    occurred_at  INTEGER NOT NULL,
    summary      TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS shell_evidence (
    finding_id       INTEGER NOT NULL,
    seq              INTEGER NOT NULL,
    verb_class       TEXT    NOT NULL,
    path_hint        TEXT,
    excerpt_redacted TEXT,
    PRIMARY KEY (finding_id, seq)
);

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_finding_occurred      ON finding (plane, category, occurred_at);
CREATE INDEX IF NOT EXISTS idx_tool_call_name_error  ON tool_call (name, error_code);
CREATE INDEX IF NOT EXISTS idx_step_stream           ON step (session_id, source_file, turn, step);
