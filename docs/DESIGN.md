# DSH Inspector — Design

**Date:** 2026-09-15 (rev 4) · **Status:** reviewed, pre-implementation
**Stack:** Java 26 · Spring Boot 4.1.1 · SQLite · Angular 22.1 + Material 22.1 · ECharts 6

> Two independent review rounds have already been through this document. Rev 2 replaced a first
> draft whose data section was mis-measured in four places; rev 4 corrected a throughput figure
> taken from a subset and presented as a corpus measurement, and fixed a privacy-boundary
> description that assigned detection work to the wrong layer. §14 records both rounds, including
> what the reviewers got wrong. The correction path is a better record than a clean
> draft would have been.

---

## 1. Problem

An agent harness (here: DSH — DeepSeek Harness) is *infrastructure a model operates*. When it
degrades, the failure does not surface as an exception. It surfaces as a model quietly doing
extra work: retrying, routing around a guard, reaching for `sed` because the intended tool
path failed an earlier step. Nothing logs an error; the session just gets slower and less
correct.

Two audiences need different answers from the same data:

- **"Is this build worse than the last one?"** — an operator rolling out a harness or model
  upgrade needs a *rate*, compared against a baseline.
- **"Why did this go wrong?"** — an engineer needs the *causal chain* behind one failure.

Existing observability gives the first shape and almost never the second, because harness
failures are cross-event: cause and symptom are separated by thousands of log lines and are
only linked by the file they both touched.

**DSH Inspector indexes a machine's local session logs and answers both: aggregate rates,
drillable to an attributed cause.**

## 2. Scope, and what is consciously not here

Built: one node, fully local. A Spring Boot application indexes local session logs (both
on-disk schema conventions, §3.2), derives typed records, runs detectors, serves a read-only
API, and renders three Angular Material routes.

Not built, by decision:

| Excluded | Why |
|---|---|
| Session-detail waterfall route | Costliest view in the UI, and it answers a question (`what happened inside this one session`) that the aggregate views already reach via the findings panel. It is the cut that buys the rest. |
| Admin/fleet plane | One machine. Building it means demoing a fiction. The seam where it would attach is real in code (§4); the plane is not. |
| Authentication | A local process reading your own logs with no remote listener. JWT here is ceremony that hides a design question. |
| File watching / streaming | A full scan is seconds to a minute (§12). Correct and boring beats clever. |
| LLM-based session analysis | The value is a deterministic, re-runnable, auditable classifier. A model judging models is not evidence. |
| Anonymising *export* transport | The redaction boundary exists and is enforced (§4); the transport does not, because there is no recipient. |

Each exclusion is an engineering argument, not a time excuse. "What did
you deliberately leave out" is only a useful question if every answer has a reason attached.

### 2.1 Time budget, stated honestly

The budget set at the start was ~3 hours. Post-cut, this build is realistically **8 hours above the line, ~9.5
with stretch** — roughly three times that, and saying so is more useful than defending a
rounder number. Two independent reviews costed the earlier draft at 10–14 hours and the machinery
removals in §2.2 are what brought it down; the estimate is still the least rigorous figure in
this document, because it is the only one that cannot be measured. It carries an explicit cut
line so that overrunning is a decision rather than a failure:

| Order | Work | ~h |
|---|---|---|
| 1 | Ingest (both conventions) + SQLite schema + findings persistence | 2.0 |
| 2 | Error-plane mapping + Detector 1 (cause attribution) | 1.5 |
| 3 | Overview + Findings API, shared filter binding | 1.0 |
| 4 | Overview + Findings UI (Material theme, table, one chart) | 2.5 |
| 5 | Fixture generator, tests, README | 1.5 |
| — | *Cut line — below this is stretch* | |
| 6 | Cohorts route | 0.7 |
| 7 | Second chart + throughput panel | 0.5 |

Step 4 is the optimistic row and it is written optimistically no longer: a Material theme
configuration, a sortable paginated findings table and a working chart wrapper are not ninety
minutes. Step 5 carries fixture scenarios that must reproduce §11's cases in the v3 event
vocabulary, which is not a one-liner.

Stop after 5 and the reviewer has a running tool, the central metric, and the privacy seam.
What is *never* cut: Detector 1's evidence chain, and the plane mapping in §5.1 — strip those
and there is nothing to discuss.

### 2.2 Trimmed as speculative, with the criterion for adding each back

A first draft of this design carried machinery built for a system this is not. The test applied
was *"who reads this today?"* — anything that failed it was removed rather than commented out,
because a component that answers to no requirement is also a component nobody defends. Each row
is a deliberate, reversible call, not an oversight:

| Removed | It would have bought | Add it back when |
|---|---|---|
| `finding.severity` | A fourth classification axis | A derivation rule exists. Undefined fields are worse than absent ones — two implementers invent two different scales. Plane carries ownership, confidence carries evidence strength. |
| `tool_call.arg_fingerprint` | A privacy-safe way to group calls by shape | A query actually groups by it. Nothing does; it was a column built for an imagined requirement. |
| `index_run` history table | Scan history for an operator | There is an operator, or a screen. The scan summary is returned to its caller and kept in `meta.last_run`. |
| Index on `tool_call.path_hint` | Fast SQL path lookup | A query filters by path. Detector 1 matches during ingest, so the index would only cost write throughput during the one write-heavy phase. |
| `version_source` enum → `version_inferred` boolean | Three states of version provenance | Upstream records the version (§9.4). Today `declared` cannot occur, so the enum was speculation styled as precision. |
| `/api/timeseries?metric=` | A generic metric endpoint | A second consumer appears. There are two series, both owned by `/api/overview`. |
| `/api/meta` | Filter vocabulary as its own resource | A second consumer appears. The rail needs it in the same load as the tiles. |
| Async index run, progress bar, job polling | Progress for a long scan | Scans stop taking seconds. A progress bar for a sub-minute wait is machinery to hide a wait that does not exist. |
| `mat-datepicker` range input | Precise custom date ranges | Someone needs precision the presets do not give. Presets fit the click-to-filter interaction (§8.2) better and cost less; `from`/`to` remain in the API, so this is a UI-only cut. |

Two things worth noticing about the list. It is **mostly removals of things that look like
rigour** — severity ratings, provenance enums, generic endpoints, run history — which is exactly
where over-engineering hides, because each is individually defensible as "good practice" and
none is individually expensive. And the criterion is symmetric: several rows name the condition
that would make them correct, so the removal is a decision with an expiry date rather than a
preference.

What was **not** trimmed, because it is cheap and load-bearing: the three failure planes (a
mapping table), the `shell_evidence` table and its config flag (the privacy claim, one table),
the architecture test in §11 (a named reflection predicate, which is what makes §4.1 enforced
rather than merely intended),
confidence tiers with an explicit unattributed state, per-convention throughput labels, dual
schema support with the `(session, source-file)` stream key, fatal-turn derivation from
`turn/end`, the ECharts wrapper, and fixture coverage for both conventions plus the
documentation-contamination case.

## 3. Data source — measured over both schema conventions

All figures below come from a pass over every session file on this machine (172.5 MB, both
conventions, 2026-09-15). Where a claim is not measured it says so. **The corpus is live** — a
second pass an hour later found one additional session — so these counts are dated, not
canonical. Anyone re-measuring should expect slightly larger numbers; this is why §11 asserts
*structure* against the real corpus and reserves count assertions for the frozen fixtures.

`~/.dsh/sessions/<project-slug>/<session-id>/` — **164 sessions**. Per session directory:

| File | Count | Size | Notes |
|---|---:|---:|---|
| `session.jsonl.zstd` (v0) | 122 | 143.3 MB | verbose, streaming |
| `session.v3.jsonl.zstd` (v3) | 45 | 29.2 MB | compact, no chunk events |
| `session.lock` | 45 | — | not data, must be skipped |

**119 sessions are v0-only, 42 are v3-only, 3 contain both.** v3 is the current convention —
every session written now is v3 — so an indexer that reads only v0 analyses history while
claiming to watch a live install.

Append-only event log, one JSON object per line: `{type, seq, time, data}`.

Measured volumes: 16,362 `tool/call` (11,004 v0 / 5,358 v3), 17,141 `tool/result`, 13,694
completed steps, 13,638 `assistant/message` carrying `usage`, 300 `turn/end` of which **48 are
fatal** (`data.reason.kind == "error"`), 254 `llm/retry` (247 v0 / 7 v3).

Throughput, measured over **all 9,036 measurable v0 steps** — a census, not a sample — with the
derivation stated rather than implied (§3.3):

| Metric | Definition | p10 | median | p90 |
|---|---|---:|---:|---:|
| decode tok/s | `usage.outputTokens` ÷ (last chunk − **first** chunk) | 37.9 | **163.4** | 226.0 |
| TTFT | first chunk − `step/start` | — | **564 ms** | 5,869 ms |

An earlier draft quoted 182 tok/s and 404 ms, measured over 40 of the 122 v0 files. That is a
subset statistic presented as a corpus measurement, it does not reproduce, and it is recorded in
§14 rather than quietly deleted. It also demonstrates why the derivation must be written down:
measuring decode from `step/start` instead of the first chunk folds prefill into the rate and
returns **122.9 tok/s** for the very same steps — a 25% error with no change in the data.

### 3.1 Typed error taxonomy — the classification backbone

`tool/result` carries a **structured error object**, so outcome classification is a switch
over a code, not text scraping:

```json
{"type":"tool/result","seq":320,"data":{"message":{…},
 "error":{"name":"FsError","code":"FS_STALE_VERSION"}}}
```

Coverage across the corpus: **267 errors / 17,141 results = 1.6%** (v0 1.4%, v3 1.8%). The
16 observed codes:

`FS_EDIT_NOT_FOUND` 107 · `FS_NOT_OBSERVED` 55 · `FS_STALE_VERSION` 36 · `FS_NOT_FOUND` 30 ·
`WEB_PROVIDER_CREDENTIAL_MISSING` 16 · `INVALID_ARGS` 6 · `SEARCH_FAILED` 5 ·
`SEARCH_INVALID_PATTERN` 3 · `FS_NOT_REGULAR_FILE` 2 · `FS_SANDBOX_DENIED` 1 · `ASK_ABORTED` 1 ·
`ABORTED` 1 · `GOAL_TOOL_AUTHORITY_REQUIRED` 1 · `TOOL_OUTCOME_UNKNOWN` 1 ·
`CODEGRAPH_UNAVAILABLE` 1 · `INVALID_TOOL_OUTPUT` 1

Codes are preferred wherever present; text patterns remain a fallback for results with no
`error` object, and any such fallback is labelled `low` confidence.

### 3.2 Two conventions, two seq spaces

A session directory can hold a v0 and a v3 file **for the same session id**, sharing
`createdAt`, with turn numbers overlapping and **`seq` restarting at 0 in the v3 file**.
Indexing both into one seq namespace corrupts every seq-based query — including Detector 1's
cause window (§5.2).

Design response: the unit of ingestion is **`(session-id, source-file)` = one event stream**,
with its own seq namespace. Findings and spans carry `source_file`. Cross-stream ordering uses
`time`, never `seq`. Only 3 sessions are affected on this machine, but the failure is silent
and total, so the model has to be right regardless of frequency.

Behavioural differences that are not cosmetic:

| | v0 | v3 |
|---|---|---|
| `assistant/chunk` / `reasoning-chunks` | present | **absent** |
| TTFT (§3.3) | derivable | **not derivable, `NULL`** |
| `llm/retry` | 247 | 7 |
| header `agentPreset` | always | sometimes absent |
| header `isSeeded` | absent | present |

### 3.3 Throughput is derived differently per convention

- **v0:** chunk events exist. Decode = `usage.outputTokens` ÷ (last `assistant/chunk` −
  **first** `assistant/chunk`); TTFT = first chunk − `step/start`. The window starts at the first
  chunk and not at `step/start`, because including prefill folds waiting into the generation
  rate: on these same steps that substitution yields 122.9 tok/s instead of 163.4. Chunk
  timestamps are batched — consecutive chunks frequently share a millisecond — so per-chunk
  rates are noise; the step is the honest unit.
- **v3:** no chunk events exist at all. Decode uses `step/start → step/end`;
  `ttft_ms` is `NULL`, not `0`. A zero would read as "instant first token" and silently
  improve the average.

Mixing the two in one aggregate is a lie of averaging, so throughput is reported **per
convention**, with the convention shown next to the number. This is a limitation of the log,
not of the tool, and it is stated on the screen rather than in a footnote.

### 3.4 Application version is not in the session header

Headers carry `{id, createdAt, cwd, delegationDepth, agentPreset, isSeeded, origin,
parentSession, version}` — and `version` is the *schema* version: **`0` in v0 files, `3` in v3
files**. That is a free independent cross-check on convention detection, which this document
previously described incorrectly as "always 0". The app version
(`0.1.5-rc.2`) exists only in the install path and `package.json`.

Consequence: version can be attributed **at index time, from the install the inspector runs
against**, but never recovered retroactively. §6 records how each value was obtained, and §7
is honest about what cohorts can and cannot show on this machine.

### 3.5 Model and context window come from an event, not the header

`request/context` carries `{provider, model, contextWindow}`: `local-impl/local-router`
(72), `local/model-27b-q6` (42), `local/local-router` (28),
`model-27b-alt-q6` (12) and others. A session can carry more than one pair — two sessions
here have several, one has four — so **last-seen wins**, which keeps the filter vocabulary
one-valued per session instead of turning the rail into a set. `context_window` is stored
alongside it. Sessions with no such
event get `NULL`, and the filter rail must render "unknown" as a real selectable value rather
than dropping the row.

## 4. Architecture

```
                    ┌─────────────────────────────────────────────────┐
 .jsonl.zstd ──▶    │ INGEST    the only layer that sees raw payloads  │
 (v0 + v3, per      │           decompress → parse → emit typed        │
  (session,file)    │         records → DISCARD the line               │
  stream)           └──────────────────────┬──────────────────────────┘
                                           │  typed records only
                    ┌──────────────────────▼──────────────────────────┐
                    │ DETECT      Detector SPI → Findings              │
                    │             (plane, category, confidence,        │
                    │              evidence refs, cause seq)           │
                    └──────────────────────┬──────────────────────────┘
                                           │
                    ┌──────────────────────▼──────────────────────────┐
                    │ STORE       SQLite + schema.sql + JdbcClient     │
                    └──────────────────────┬──────────────────────────┘
                                           │
                    ┌──────────────────────▼──────────────────────────┐
                    │ SERVE       read-only REST  +  Angular Material  │
                    └─────────────────────────────────────────────────┘
```

### 4.1 The privacy boundary, and its one named exception

Ingest is the only component that may hold a decompressed line. Everything downstream receives
typed records, and those types **have no field for chat message content**.

One exception is unavoidable and is therefore designed rather than smuggled: Detector 1 must
see *shell command text* to attribute a cause. Handing `ToolCallRecord` a raw `arguments`
string would make the architecture test in §11 false on the design's own terms, and commands are
where secrets actually live (`cat .env`, a token in a heredoc).

**Resolution: ingest performs command *analysis* and emits a narrow typed `ShellEvidence`
record.** Which side owns what has to be stated exactly, because an earlier draft said "ingest
performs the path matching", and that is incoherent: the path that failed is unknown until the
`FS_STALE_VERSION` result arrives, so ingest cannot match against it, and verb classification is
pattern matching on raw text, which only ingest may perform. The split is:

| Layer | Owns |
|---|---|
| **INGEST** | per shell call: extract referenced paths, classify the verb, truncate and redact → emit `ShellEvidence{seq, referencedPaths[], verbClass, redactedExcerpt}`. Per file-tool call: emit a typed record with `path_hint`. Discard the raw line. |
| **DETECT (D1)** | touched set from the typed file-tool records (no raw text needed); stale-touch derivation; window search over `referencedPaths ∩ {failedPath, basename}`; first-mutation tie-break; category and confidence; which evidence to attach. |

Ingest therefore produces **unmatched, classified** observations; the detector does all the
reasoning. `ShellEvidence` carries a set of referenced paths rather than a single matched one,
precisely because matching is the detector's job. Consequences:

- The strict property holds: no type outside `ingest` carries *conversation* content, and the
  one text-carrying type that exists is `ShellEvidence`, which is defined and stays inside it.
- The redaction/truncation decision is made once, at the boundary, instead of at every read.
- When a second plane exists, `ShellEvidence` is the type whose excerpt field is switched off
  for export. The boundary in the code is the boundary on the wire.

Evidence excerpts live in their own table and are reachable **only** from
`GET /api/findings/{id}`, never from an aggregate response. `inspector.evidence.store` is a
boolean: `true` locally (default, redacted), `false` for anything exportable. Aggregates stay
shippable; evidence stays home.

That is the answer to "what does the anonymisation actually buy" — and it is a better answer
than a redaction pipeline that quietly keeps everything.

### 4.2 Why no ORM, and no migration engine

Both omissions are the same argument applied twice: a dependency has to pay for itself at the
scale this project actually is.

**No JPA.** The workload is `GROUP BY` over findings and spans. An ORM adds a dependency,
hides the SQL a reviewer wants to read, and manages an aggregate-root lifecycle that does not
exist here. `JdbcClient` owns the reads.

**No Flyway.** A migration engine earns its keep at `V2__add_cohort.sql`, when two environments
disagree about the schema. This project has exactly one migration, so Flyway would be
configuring a tool to solve the problem of having used that tool. The cheaper shape: DDL in
`schema.sql` via `spring.sql.init`, written idempotently (`CREATE TABLE IF NOT EXISTS`), plus a
single `meta(key, value)` row carrying `schema_version` (§6). That row is the honest hook — it
makes "a real install versions these" mechanical later without importing an engine now, and
adopting Flyway is a §9 next step rather than a shipped dependency.

There is also an unpriced risk that settles it: Flyway 13.x is newer than whatever Boot 4.1's
BOM manages, SQLite support lives in a separate `flyway-database-sqlite` module since v10, and
Boot runs migrations eagerly at startup. If that combination has rough edges, the time goes on
the component that buys the least.

**SQLite over H2** is a shape choice, not a production-readiness one: no server and no setup
for a reviewer cloning the repo; CTEs and window functions so the aggregate queries stay
idiomatic SQL rather than becoming stream-reduce loops in Java; and — decisively — the file is
a standard artifact. §4.1 promises a content-free, exportable bundle, and "this is that bundle"
is a claim you can demonstrate by opening it in `sqlite3` on the spot. An H2 file cannot be
inspected with standard tooling, which would turn the strongest privacy claim in the design
into an assertion about code.

## 5. Detection model

### 5.1 Three failure planes, mapped from the typed codes

A raw error count makes a useless dashboard here, because three unlike things all arrive as
"the tool said no". Every finding is assigned a plane, from §3.1's codes:

| Plane | Codes / events | Owner | How to read it |
|---|---|---|---|
| **Infrastructure fault** | `WEB_PROVIDER_CREDENTIAL_MISSING`, `CODEGRAPH_UNAVAILABLE`, `TOOL_OUTCOME_UNKNOWN`, `INVALID_TOOL_OUTPUT`; **any** `llm/retry` failure code; fatal `turn/end` | operator | **Counts.** A spike is a broken build. This is the "the upgrade broke a plugin" signal. Observed retry codes: `TIMEOUT` 190, `TRANSPORT` 34, `SERVER` 30. |
| **Guard rejection** | `FS_STALE_VERSION`, `FS_NOT_OBSERVED`, `FS_SANDBOX_DENIED`, `GOAL_TOOL_AUTHORITY_REQUIRED`, approval-required | *nobody* — the harness worked | **Rate against baseline.** A denial is a success for the harness and a cost for the model. A rising rate after rollout means the model drifted or the guard tightened. |
| **Model misuse** | `FS_EDIT_NOT_FOUND`, `FS_NOT_FOUND`, `INVALID_ARGS`, `SEARCH_INVALID_PATTERN`, `SEARCH_FAILED`, `FS_NOT_REGULAR_FILE` | model / instructions | Rate, attributed to a detector that explains intent. |

The middle row is the subtle one and worth five minutes in the room: a sandbox denial is a
*success* for the harness and a *cost* for the model, so it is charted as a rate against a
baseline, never as an error count. A raw count tells an admin nothing; a rate that jumped
after a rollout tells them everything.

### 5.2 The documentation-contamination trap

This is the finding that most changed the design, and it is a general lesson about log
analytics.

Grepping the corpus for `media_budget_exceeded` yields **427 lines**. The number of turns that
actually died of it is **6** — recovered from `turn/end` events with
`data.reason.kind == "error"` carrying `400: {"code":"media_budget_exceeded"}`. The other 421 are
spread across **nine** event types — `user/message` 226, `agent/inbox/spliced` 82,
`assistant/message` 37, `compaction/summary` 26, `tool/result` 25, `tool/call` 19,
`assistant/chunk` 3, `assistant/attempt` 3 — which is to say the contamination is not limited to
injected context: **the harness's own instruction document names the error code, that document is
replayed into every session, and then the model and its tools quote it back.** A text-pattern
metric would report 427 fatal errors where 6 occurred — a 70× overcount.

`context_length_exceeded` is worse: **129 text hits, 0 on any `turn/end`**. On this corpus it
is pure documentation, so that bucket is **fixture-only** and labelled as such rather than
allowed to look measured.

Three rules follow, and they are the reason this section exists:

1. Classify from typed codes and typed events. Text matching is a labelled `low`-confidence
   fallback only. One necessary exception: on all six real fatal turns the *typed* code is generic
   (`INVALID_REQUEST`) and the specific one is embedded in the message string —
   `400: {"code":"media_budget_exceeded", …}`. Extracting it is a **fixed parse of that prefix**,
   a typed rule with a defined grammar, not free-text matching; the distinction is that the parse
   either matches its documented shape or produces nothing.
2. Fatal turns come from `turn/end.data.reason.kind == "error"`. That event is the real
   signal for "the turn just died", which is precisely the failure mode with no error log.
3. Any candidate pattern that also appears in the harness's instruction files is treated as
   contaminated until proven otherwise. The fixture corpus contains a session whose context
   includes that documentation, so the contamination is a **regression test**, not just a
   caution.

### 5.3 Detector 1 — stamp-guard violations with cause attribution

*The centrepiece: the metric is not the count, it is the cause.*

DSH's file tools keep a per-session read record stamped `dev:ino:size:mtimeNs:ctimeNs`; a
write is refused when the stamp no longer matches, surfacing as `FS_STALE_VERSION` — **36 real
instances on this corpus** (24 v0, 12 v3). The rejection states something precise: between the
last file-tool operation on path P and this write, P's metadata changed and no file tool did
it.

What the log gives you and what you derive, stated exactly, because the distinction is the
whole credibility of the detector: the log gives **the failed write and the path** (the
rejection text carries no seq; the write's seq comes from the event itself and
`sourceEventSeqs`). The **stale touch is derived** — it is the last file-tool operation on
that path before the failure. One half is data, the other is inference, and the spec says so
rather than claiming the log "tells you both halves".

Algorithm, per event stream (§3.2), streaming:

1. Maintain a touched-set: path → seq of the last **file-tool operation** (`read`, `write` or
   `edit`) on it. *Operation, not read* — on 8 of 10 sampled rejections the last touch was an
   `edit` or a `write`, and for a file created by `write` a read-only tracker finds nothing at
   all.
2. On `FS_STALE_VERSION`, emit a finding `{path, failedSeq, staleSeq}`.
3. Search `ShellEvidence` in `(staleSeq, failedSeq)` **within the same stream** for that path
   (absolute path *and* basename), and take **the first mutating command after the stale
   touch** as the cause — it is the one that moved the stamp. Later matches are noise. Real
   windows contain up to 9 matches, so this tie-break is load-bearing and cannot be left
   implicit.
4. Classify:

| Classification | Meaning | Confidence |
|---|---|---|
| **direct-mutation** | shell wrote the file outside the file tools (`sed -i`, `>`, `tee`, `python … .write()`, `cp`, `mv`) — the intended-workflow violation, with the command as evidence | high |
| **vcs-restore** | `git checkout/restore/stash/reset` touched it — legitimate work the stamp cannot know about, **not** a violation | high |
| **mention-without-mutation** | path appears in the window but only in read-only position (`wc -l`, `ls`, `sed -n`, `grep`, `cat`, or mere execution) | → external |
| **other** | path referenced but unclassifiable, including a bare script or program invocation (`bash x.sh`, `node y.mjs`) | never mutating — see script opacity below |
| **external** | no in-window reference at all | stored as unattributed |

`mention-without-mutation` is not a rounding detail: real windows contain exactly such
mentions, and an earlier draft that treated any mention as a cause produced false positives on
them. It is classified external and the mention is *not* stored as evidence.

`other` exists because the verb table is a grammar, not a list of examples, and every input must
land somewhere. Note that `rm` followed by recreation *is* mutating — the stamp covers
`ino`, not just `mtime` — while bare execution is not, even though a script it launches may
mutate. That asymmetry is the honest limit below, and it is a classification, not a guess.

Confidence is a property of the evidence, never a guess: absolute path plus mutating verb is
`high`, basename plus mutating verb is `medium`, anything else is `external` and stores
`confidence = NULL`, which the UI renders as **unattributed** rather than a percentage bar.

**Measured false positive, kept as a regression fixture.** `audit-report.mjs` was
flagged with `node scripts/audit-report.mjs` as its last in-window mention — *executing*
a script does not touch its mtime. Basename matching fired wrongly.

**Known limits, stated before someone finds them:**

- Attribution is **abductive**. The log never records what modified the file. Findings say
  "consistent with", never "caused by", and the UI shows the evidence instead of asking for
  trust in the label.
- **Script opacity.** When a script mutates a file (`bash regen.sh` rewriting
  `sim.mjs`), the command text never says so. This yields a false *negative*, and no
  amount of pattern work fixes it — the information is not in the log.
- **Windows can be enormous.** Real sampled windows span 61k–174k seqs. Candidate search is
  bounded by the touched-set index, not by scanning the window.
- Relative paths and shell variables force basename matching, which is where the false
  positives live; that trade-off buys recall and is why confidence is per-finding.
- **A subagent that wrote the file appears in a different session stream entirely.**
  Cross-stream causality is invisible today, even though headers already carry `parentSession`
  and `origin`. Correlating them is the first item on §9.

### 5.4 The rest of the detectors

| # | Detector | Plane | Cost | Notes |
|---|---|---|---|---|
| 2 | Error rates by tool × code × plane | all three | cheap | A `GROUP BY` over §3.1. The regression view. |
| 3 | Fatal turns and retries | infra | cheap | Fatal `turn/end` with the parsed code (§5.2). A **retry storm** = ≥2 `llm/retry` within one `(turn, step)`, i.e. a step that exhausted its budget (`maxRetries` is 2 in this corpus) → one finding per step, never one per retry. Throughput is not a detector; it is a span measurement (§3.3) read by the stretch panel. |
| 4 | Generalised intent-drift rule engine | misuse | **documented, not built** | Declarative rules over the touched-set: repeated denials without escalation, delegation used for perception, `grep` where `read` exists. |

D4 stays unbuilt deliberately. It is the weakest signal in the system, and generalising it
into a rule language would dress a heuristic up as a fact. One instance ships *inside* D1 as
proof; the language remains on paper, which is a stronger position than shipping it
unvalidated.

### 5.5 Extension point

`Detector` is the SPI: declare the event pattern watched, the evidence extractor, the plane,
and one sentence of meaning. **Adding a detector is one class plus one registry entry**, and
the answer to "what would you extend?" is *more detectors* — the room to grow is built, not
promised.

## 6. Data model

```
session    (id, source_file, project_slug, schema, started_at, ended_at, agent_preset,
            delegation_depth, model, context_window, harness_version, version_inferred,
            indexed_at, fatal_turns)
step       (session_id, source_file, turn, step, started_at, ended_at,
            input_tokens, output_tokens, decode_tps, ttft_ms NULL on v3)
tool_call  (id, session_id, source_file, turn, step, seq, name, started_at, ended_at,
            duration_ms, error_code, plane, path_hint)
finding    (id, session_id, source_file, detector, plane, category, code,
            confidence NULL when unattributed, path_hint, seq, stale_seq, cause_seq,
            created_at, summary)          -- summary: generated sentence, no user text
shell_evidence (finding_id, seq, verb_class, path_hint, excerpt_redacted)   -- config-gated §4.1
meta       (key, value)   -- schema_version, and nothing else
```

Five tables. `meta` holds exactly one row. `last_run`, `corpus_root` and `evidence_store` were
removed on the same test as the `index_run` table in §2.2 — nothing reads them: the scan summary
is returned by `POST /api/index/run`, the corpus root is a startup configuration value, and the
evidence flag is read from configuration, not from the database. Storing configuration in a
database table because a database table is available is the same mistake one level down.

`meta.schema_version` is the migration hook that makes §4.2's "no engine yet" an honest
deferral rather than an omission: the DDL is idempotent for a fresh file, and the version row
is what a real install would branch on when a `V2` exists.

`(session_id, source_file)` is the stream key everywhere (§3.2); no seq-based comparison is
ever performed across streams.

`session.model` and `context_window` come from `request/context` (§3.5); `NULL` where absent,
and "unknown" is a first-class filter value. `harness_version` is paired with a single boolean
`version_inferred`, because a session indexed today from a 0.1.5-rc.2 install **inherits that
version even if it was written months ago by something else** (§3.4). The flag stops that
inference from silently contaminating the cohort view.

It is deliberately a boolean and not a `{declared, inferred, unknown}` enum. The `declared`
state is unreachable until DSH begins writing an app version to the session header — §9 item 4
— so every row on any current install would carry `inferred`, and an enum whose states cannot
occur is speculation styled as precision. The boolean says the one true thing: *this value was
not recorded, it was assumed.*

The `step` table's token and throughput columns have **no reader above the cut line** — the
throughput panel and the cohort comparison that consume them are stretch items 7 and 6. They are
built anyway, deliberately: ingest writes them while the stream is open, and adding a column
after the fact means re-indexing 172 MB to find out whether the panel is worth having. That is a
different justification from speculative machinery — the cost is paid once at write time, not
repeatedly in complexity — but it is still a call against the rule in §2.2 and it is labelled as
one.

Indexes on `finding(plane, category, created_at)`, `tool_call(name, error_code)`,
`step(session_id, source_file, turn, step)`. There is deliberately **no index on
`path_hint`**: Detector 1 builds its touched-set while streaming during ingest, so no SQL
query looks up by path. Indexing for a query that does not exist costs write throughput for a
workload that is write-heavy during indexing.

`path_hint` is project-relative, never absolute — absolute paths carry the username.

## 7. API

Read-only, plus one mutating endpoint. Four GETs, not seven.

```
GET  /api/overview?from&to&schema&model&preset&harnessVersion
       tiles, plane mix, top detectors, the two chart series (daily buckets),
       and the filter vocabulary the rail needs
GET  /api/findings?plane&detector&session&code&from&to&sort&page&size
GET  /api/findings/{id}      finding + evidence chain (only endpoint that returns text)
GET  /api/cohorts?groupBy=harnessVersion|model|schema|preset&baseline=<key>
POST /api/index/run          synchronous scan, returns the summary
```

There is no `/api/timeseries?metric=…` and no `/api/meta`. A metric-keyed generic series
endpoint is a framework, and there are exactly two series, both owned by `/api/overview`; a
separate vocabulary endpoint exists only to be fetched by the same page load that fetches the
tiles. Collapsing them removes a parameter space to validate and a request that must be kept
in sync with the first. **Re-add either when a second consumer appears** — that is the actual
criterion, not whether the endpoint looks general.

**Where the filter vocabulary comes from.** `/api/overview` returns it: the five rail values, plus
the observed error `code` values and `detector` ids that the Findings filter needs, because those
are corpus-dependent and hardcoding the 16 codes would be wrong by construction. `plane` is the
exception — exactly three values, a UI constant, not a round trip.

`POST /api/index/run` is synchronous and returns its summary, which the UI shows in a snackbar.
A full scan takes seconds (§12), so there is no job to poll: an async run, a progress bar and a
run-history table would be machinery to hide a wait that does not exist.

Every GET shares one `InsightFilter` binding, so filtering is one contract rather than seven
ad-hoc query params. Errors are RFC 9457 problem details. An unknown filter value is a 400
carrying the allowed set — a wrong filter must fail loudly, because a silently-empty dashboard
reads as "no problems found", the most dangerous wrong answer this tool can give.

**`/api/cohorts`, honestly.** It is one `GROUP BY` and it is the right abstraction for the
operator's question, but on *this* machine at delivery it has **one harness version and one
install**, so grouping by version yields a single row. Its value on real data is as a
**model/schema** comparison — and there the schema split is genuinely interesting, since
§3.3 shows throughput is not comparable across conventions. Rates are **findings per 1,000 tool
calls**, and deltas are shown in percentage points against the chosen baseline cohort — raw counts
would just report which machine has been running longer. A harness-version demo requires
the fixture corpus, which ships two synthetic versions. The screen states which basis it is
showing. A tool that silently presents a one-row comparison as a regression analysis has
answered a question it was not asked.

## 8. Frontend

Angular 22.1, standalone components, signals, zoneless. **No NgRx** — state is one
`FilterState` service of signals plus per-route computed views; a store library for three
routes and one shared filter is a dependency and a conceptual layer for nothing.

### 8.1 Design language: Angular Material

**Angular Material 22.1.6 is the design language** — modern and flush rather than hand-rolled.
Elevation, focus management, keyboard navigation and accessible form controls are solved
there, which on this budget is work not spent; and the surface is chrome-heavy (filters,
tables, panels), exactly what Material is for.

| Need | Component |
|---|---|
| Global filter rail | `mat-sidenav`, persistent at desktop width |
| Filters, time range | `mat-form-field` + `mat-select` — preset ranges: 24h / 7d / 30d / all |
| Findings / Sessions tables | `mat-table` + `mat-sort` + `mat-paginator` |
| Plane and confidence indicators | `mat-chip` |
| Route framing, index action | `mat-toolbar`, `mat-snack-bar` |
| Evidence side panel | `mat-drawer` |

**Theming is deliberately left to whoever implements it.** The spec fixes the language, not
the look. Guidance rather than mandate, because these are the levers that matter here:

- Use the current token-based theme entry point — `@use '@angular/material' as mat;` with
  `mat.theme((color: …, typography: …, density: …))`, which is what 22.x exposes. The old
  `prebuilt-themes/…css` import still ships in 22.1.6 but signals unfamiliarity with the
  current stack.
- **Density is the lever that makes this read as an engineering console.** Pulling Material's
  density token down one or two steps turns a roomy Material app into a dense analysis tool,
  cheaper and better than hand-adjusting paddings.
- A dark palette suits a monitoring surface, but plane colours must stay separable in it —
  three hues that collapse under dark theming would break the one distinction that carries
  meaning.
- Tabular numerals in every metric column. A dashboard whose tok/s column jitters as it
  re-renders looks broken even when the numbers are right.

Charts: **ECharts 6 behind a ~40-line Angular wrapper.** Material ships no chart component
(verified against its published exports), so Material owns the chrome and ECharts owns the
plot area, themed to the same palette. ECharts rather than `@swimlane/ngx-charts` because 25.0.2 pins peers to
`^21.2.0 || ^22.0.0` and will age out on the next Angular major — a claim that needs the scope
named, since the *unscoped* `ngx-charts` on npm is a deprecated Angular-2.4-era package and a
check against that name appears to prove something else entirely. Material itself peers
`^22 || ^23`, which is the coupling behaviour the wrapper wants anyway.

### 8.2 The routes

**Overview** (default) — filter rail; tiles (sessions, tool calls, fatal turns, findings per
session); findings stacked by plane over time; throughput **per convention** with the
convention labelled. Below, detectors ranked by volume. **Clicking a chart element applies a
filter rather than navigating.** Analysts keep their time range and change what they look at;
navigating away on click destroys the comparison in progress.

**Findings** — dense table: plane chip, code, detector, tool, path, session, time; confidence
rendered as a bar **or as "unattributed"** (§5.3). Row click opens a `mat-drawer` with the
causal chain and its seqs — the failed write, the derived stale touch labelled as derived,
the candidate command, and the sentence explaining the confidence. *A finding that cannot show
its evidence should not be on screen.*

**Cohorts** — grouping selector, normalised comparison against a chosen baseline, deltas on
violation rate, plane mix and throughput. Banner when the basis is `version_inferred`
(§6) or when grouping yields one row (which is the case for harness version on real data,
§7).

Visual language: an engineering console, not a marketing dashboard. One rule that is not a
preference: **plane colour is carried consistently from chip to chart to marker**, because a
finding's plane is the difference between "someone's deploy broke" and "the model is being
difficult". **Mobile is out of scope** — a desktop analysis tool, and pretending otherwise
spends the budget on a layout no reviewer opens on a phone.

## 9. Open points, in priority order

1. **Cross-stream causality.** Correlate `parentSession`/`origin` so a subagent's write
   explains a parent's `FS_STALE_VERSION`. The fields exist; the correlation does not. It is
   the largest known blind spot in Detector 1.
2. **Script opacity.** Cause attribution fails when a script performs the mutation (§5.3). The
   information is not in the log; an exec-audit or filewatch side-channel would supply it.
3. **v3 first-token timing.** No chunk events means no TTFT on current sessions (§3.3). A
   `first_token_at` on `assistant/message`, or coarser chunk batching, restores it.
4. **App version in the session header.** §3.4: attributable forward, never retroactively. One
   field removes a whole class of "we can't answer that about history".
5. **The rule language for intent drift** (D4), once the shipped heuristics have produced
   enough labelled true/false positives to validate against.
6. **Confidence calibration.** Confidence is currently a rule over evidence shape. With a
   labelled set it could be scored, making the ranking honest rather than nominal.
7. **Adopt a migration engine at the second migration.** §4.2 defers Flyway deliberately; the
   `meta.schema_version` row exists so that adopting it is a mechanical step rather than a
   schema archaeology task once `V2` is real.

*Retired:* an earlier draft listed "add `isError` upstream" as the top open item. §3.1 shows a
typed code already exists. Deleting that item is the clearest evidence of what the review
loop caught.

## 10. Enterprise outlook — the seam, and what it costs

The path from this to "one admin, many machines" is one line: **move the ingest→detect
boundary across a network.** Everything left of it stays on the developer's machine, because
only it can see raw content; everything right of it is already content-free.

What that costs, honestly:

- **Nothing in the domain model.** Findings and spans are content-free by construction.
- **One transport choice** — agent push vs admin pull, batching, retry — and a store per tenant.
- **`shell_evidence` must not ship by default** (§4.1). Aggregate-only export is the default,
  and `inspector.evidence.store=false` makes a bundle safe by configuration rather than by
  discipline.
- **Identity that does not exist today.** Cohorts need a stable machine id that is *not* a
  username — a per-install random id, exactly the trade `~/.dsh/.anonymous-user-id` represents.
- **Authorisation.** Multi-tenant means per-user scoping and an admin role; a JWT becomes
  load-bearing the moment a session id leaves the machine it came from.
- **A schema-contract problem, now proven.** §3.2 found two on-disk conventions with different
  capabilities on *one* machine. Across a fleet of machines at different versions, ingest
  becomes a compatibility matrix and every metric needs a "which versions can report this"
  answer — which is what §3.3 already does for one machine, generalized.

The one claim this design deliberately does not make: "the enterprise version works". It says
"here is the exact line that moves, and here is what moving it costs". For a system whose
premise is that personal content never leaves the developer's machine, that is the more useful
claim.

## 11. Testing

- **Fixture corpus in-repo.** Synthetic `.jsonl.zstd` files from a committed generator, seeded
  with each detector's target pattern — including the `mention-without-mutation` case, the
  `vcs-restore` case, the measured false positive from §5.3, a **session whose context
  contains documentation naming an error code** (§5.2), and both schema conventions including
  a directory holding both files for one session id.
- **Detector unit tests** over hand-built event sequences, with window-boundary cases: a cause
  *before* the stale touch must not be attributed; a second mutation after the first must not
  win.
- **Privacy boundary test:** a reflection scan over public types asserting a named predicate —
  *no public field outside `ingest` whose declared type is `RedactedExcerpt` or whose name is on
  the content list (`message`, `content`, `text`, `arguments`)*. `ShellEvidence` is given a
  `RedactedExcerpt`-typed field so the whitelist is by **type**, not by package placement;
  scanning by package would merely bless whatever ingest decides to export. Being precise about
  the predicate matters because an unnamed "architecture test" degrades into a name-lint the
  moment someone asks what it matches — and this is an architecture test, not property-based
  testing, which means something else.
- **Mapper round-trip tests** for the hand-written SQL — no ORM to trust.
- **`--dsh-home` smoke run** against the real corpus: parse every file, assert zero parse
  failures and that every emitted error code is one §3.1 knows. It asserts **structure, not
  counts** — the corpus is append-only and live, so count bands would drift as sessions are
  written. Count bands belong to the frozen fixture corpus.

Not tested: the Angular layer beyond "it builds and renders". No time budget pays for a
browser test harness in three hours; a real gap, named rather than omitted silently.

## 12. Running it

```bash
./run.sh                     # backend :8080, Angular dev server proxying /api
java -jar backend/target/*.jar --inspector.dsh-home=$HOME/.dsh   # real sessions
```

Default data source is the committed synthetic corpus, so a reviewer sees a populated
dashboard — with real-looking violations and both schema conventions represented — on first
run, without installing DSH. `--dsh-home` points the same indexer at real sessions for a live
demo. That split makes the repo reviewable in ten minutes while the numbers stay demonstrably
real, and it is why nothing personal (session content, project names, `AGENTS.md`) is in this
repository.

**First run needs no button press: on startup, if the database has no sessions and the configured
corpus has files, the application runs the index synchronously and logs the summary.** Without
that rule, "clone and run" depends on the reviewer finding the Index action, and the promise that
`./run.sh` shows a populated dashboard quietly fails on one implementer's interpretation and
succeeds on another's.

Full scan of the 172.5 MB corpus: **tens of seconds**, and **parse-dominated** — decompressing all
173 MB measured 2.3 s, so JSON parsing and persistence are the cost, not zstd. Either way §2's
exclusion of file watching stands: the cheap correct thing is genuinely cheap here.

### 12.1 Repository layout

Directories are named by **role, not framework**, so the shape of the system is readable before
any file is opened, and renaming a stack decision later does not rename the tree:

```
dsh-inspector/
├── README.md                 ← what this is, quickstart, the 3–5 decision sentences
├── run.sh                    ← the one command; orchestrates both sides, so it lives at root
├── .gitignore
├── docs/
│   ├── DESIGN.md             ← this document
│   └── AI-NOTES.md           ← prompts, the review loop, what it caught and what it got wrong
├── backend/                  ← Spring Boot: ingest → detect → store → serve
│   ├── pom.xml
│   └── src/main/resources/fixtures/     committed synthetic corpus (§12)
└── frontend/                 ← Angular 22 + Material
    ├── package.json
    └── src/app/
```

Three directories, and everything a reader needs sitting at the root. Two deliberate
consequences:

**`docs/` contains only what a reviewer wants.** No process scaffolding, no dated spec archive,
no tooling-generated trees — the design doc and the AI working notes, both named for what they
are. Notes on how the AI tools were used are a document of their own, and `AI-NOTES.md` is a first-class
deliverable rather than a section buried in the README.

**The fixture generator lives in `backend/`, not in a root `tools/` directory.** It is a small
Java `main`, because `zstd-jni` is already a backend dependency, so writing `.zstd` fixtures
costs nothing and the repo keeps one language per directory instead of a Node script that must
agree with Java about the event schema. That also forces the important property: fixtures and
the real `--dsh-home` corpus go through **the same ingest code path**, so the demo corpus cannot
drift into a shape the indexer happens to like. A separate root `tools/` would have been the
natural place and would have quietly invited a second, simpler parser.

Nothing in the tree exists to satisfy a convention that earns nothing (§2.2, §4.2), and no
empty directories are committed — they appear with the first commit of code.

## 13. Versions

| Component | Version | Verification |
|---|---|---|
| Java | 26 | Spring's own System Requirements page: "requires at least Java 17 and is compatible with versions up to and including Java 26". Separately, `javac --release 26` works on this machine — which proves the local toolchain, not Spring's range. |
| Spring Boot | 4.1.1 | Maven Central metadata (4.2.0 at M1); Spring Framework 7.0.9 |
| SQLite (`sqlite-jdbc`) | 3.53.4.0 | Maven Central |
| Schema | `schema.sql` + `spring.sql.init` | No migration engine — see §4.2. `sqlite-jdbc` is the only persistence dependency. |
| `zstd-jni` | 1.5.7-16 | Maven Central |
| Angular / CLI | 22.1.x / 22.1.8 | npm `latest` |
| Angular Material | 22.1.6 | npm `latest`; peers `@angular/core ^22 ‖ ^23`; CDK same 22.1.6 |
| **TypeScript** | **~6.0** | ⚠ npm `latest` is 7.0.2 but `@angular-devkit/build-angular@22` requires `>=6.0 <6.1`. Pin explicitly. |
| ECharts | 6.1.0 | npm `latest` |
| Node | ≥26 | CLI engines allow `^22.22.3 ‖ ^24.15.0 ‖ >=26.0.0` |

The two SQLite specifics worth pinning down in the build: `spring.sql.init.mode=always` with
fully idempotent DDL (it runs on every start against an existing file), and `journal_mode=WAL`
with a `busy_timeout`, because the index run writes while the API reads. Neither is exotic;
both are the kind of thing that reads as a bug if discovered at demo time.

## 14. How this spec was checked

Recorded because how the AI assistance was used is part of what this project is about, and the process is more
informative than the prose.

1. **Explore before designing.** Read the real log format first; the first draft's detector
   design was built against actual instances of `file changed since it was read`.
2. **Draft, then self-review.** Found two defects: a `.gitignore` that excluded the fixture
   corpus while §12 promised a populated first run, and an unreconciled conflict between a
   version filter and "version is unknowable" — which produced `version_inferred` in §6. It did
   **not** catch over-engineering; that took a direct challenge from the author, which is the
   blind spot to remember: self-review finds contradictions, not ambitions that were never
   earned.
3. **Independent review by a second model** (different model, no shared context), instructed to
   hunt contradictions, infeasibility, technical impossibility and delivery risk. It found
   four factual errors that all passed a 10-minute re-measure, including two that overturned
   design premises: the structured error object (§3.1) and the second schema convention
   (§3.2). It separately costed that draft at 10–14 hours, which confirmed it was over budget;
   the §2.2 machinery removals are what brought the estimate down, and the §2.1 cut line is a
   protection device for the core, not the mechanism that produced a 3-hour-shaped plan.
4. **Verify review findings before accepting them.** Two of its claims were wrong and did not
   survive re-measurement: it reported `llm/retry` absent from v3 (there are 7) and the model
   as unrecoverable (`request/context` carries it, §3.5). It also reported 167 files against
   164 sessions, and treated the AGENTS.md-sourced `media_budget_exceeded` counts as possibly
   real, which prompted §5.2.

5. **A second fresh reviewer on rev 3, then the same verification discipline applied to it.** It
   found that §3's throughput figures did not reproduce — they came from 40 of 122 files and were
   published as a corpus census — plus a header-version claim that was false for half the corpus,
   an incomplete enumeration of the contamination carriers, and a privacy boundary in §4.1 whose
   own wording could not be implemented as written. It also produced one false finding of its own:
   it reported that ngx-charts has no Angular-22 release, having queried the unscoped deprecated
   `ngx-charts` rather than `@swimlane/ngx-charts`, whose published peers are exactly as cited.

Both directions of that loop matter. A reviewer model with fresh context caught errors I had
committed to prose, and I caught errors it had committed to a report. Nothing entered this
document that was not re-measured on disk — which is the actual answer to "how do you trust
AI-assisted output": not by trusting the first draft, mine or its, and not by arguing from
plausibility, but by re-running the measurement.
