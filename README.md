# DSH Inspector

DSH Inspector indexes the local session logs of the DeepSeek Harness (DSH) — the agent's
own infrastructure — and renders them as a read-only web dashboard of failure rates and
attributed causes. It answers two questions: "is this build worse than the last one?",
answered as a rate against a baseline cohort, and "why did this one go wrong?", answered as
the causal chain behind a single finding.

## What it refuses to do

- **No conversation text anywhere in the store.** The schema has no column that could hold
  a message body. The one endpoint that returns any text at all is the finding-detail
  endpoint, and it returns only the truncated, credential-masked command excerpt of the
  evidence — a type that lives inside the ingest layer and never crosses the API boundary.
- **No raw tool arguments.** Detectors see paths, verbs, sequence numbers and outcomes;
  argument payloads never leave ingest.
- **No corpus in this repository.** `corpus/` is gitignored. The committed fixtures are
  generated with invented paths and project names, and nothing personal is in the tree.

The first two refusals are enforced, not assumed: `PrivacyBoundaryTest` walks every compiled
main class and fails if a content type or a content field name appears outside
`inspector.ingest`.

## Quickstart

```bash
git clone <this repository>
cd "DSH Inspector"
./run.sh
```

Open http://127.0.0.1:4300. That is the whole manual step: if `backend/target/*.jar` is
missing, `run.sh` builds it, and on first run the backend indexes the committed fixture
corpus, so the dashboard comes up populated — no database, no jar, no flag. Ctrl-C stops
both processes.

## Pointing it at a real corpus

The flag is `--inspector.corpus=/path/to/sessions`, and it resolves against the JVM's
working directory — which is why `run.sh` starts the JVM inside `backend/` (and why the
committed default is the relative path `fixtures/sessions`). From `backend/`, against your
own session directory:

```bash
mvn -q -DskipTests package
java -jar target/dsh-inspector-0.1.0.jar --inspector.corpus=/path/to/sessions
```

then `cd frontend && npm start` and open http://127.0.0.1:4300. The index is derived data:
the SQLite file is a cache of the corpus, and a schema-version mismatch wipes and
re-indexes it rather than migrating.

## Tests

```bash
cd backend && mvn test                 # 145 tests, 1 skipped
cd frontend && npm test -- --watch=false   # Vitest 4 + jsdom, run through the Angular builder
```

## Ports

| Port | What |
|---|---|
| 8091 | backend, bound to 127.0.0.1 only |
| 4300 | Angular dev server; proxies `/api` to 8091 |

The ports are a design constraint, not a default: the inspector runs beside the tool it
observes, on a machine where the obvious ports are already owned. A collided port fails at
startup with the port named in the message.

## Deliberately left out

- **Authentication.** A local process reading its own logs; the design defers the question
  until a session id could actually leave the machine (docs/DESIGN.md, "Not built, by
  decision", and §10).
- **Incremental indexing / file watching.** A full re-index of the measured 173 MB, 168
  stream corpus takes 4.3 s, so watching would be machinery to hide a wait that does not
  exist (docs/DESIGN.md §2, §12).
- **A datepicker.** Four presets (24 h / 7 d / 30 d / all time) cover the click-to-filter
  interaction; `from`/`to` remain in the API, so it is a UI-only cut (docs/DESIGN.md §8).
- **CORS for a statically served frontend.** The dev server proxies `/api`, so same-origin
  holds in development. There is no static deployment with a recipient today, so a CORS
  whitelist would be a seam with no one on the other side (docs/DESIGN.md §2, §10).
- **Migrations.** One SQLite file, with a schema-version gate that wipes and re-indexes. A
  migration engine is deferred until a second schema version is real (docs/DESIGN.md §4.2,
  §9).

## Decisions and trade-offs

- The indexer keeps raw session lines only inside the ingest layer, and the store has no
  column that could hold conversation text; that boundary is enforced by a test that
  reflects over every compiled main class, not by a convention someone might forget.
- A stamp-guard violation is attributed to the first command, chosen by sequence number, in
  the open interval between the stale stamp and the refused write that could have moved the
  stamp — never by which verb looks worse; a `git restore` that precedes a mutation stays
  the cause, because it is the one that invalidated the stamp.
- Rates are findings per 1,000 *observed* tool calls, excluding the 794 of 17,244 rows that
  are a result with no matching call, because a denominator that counted them would
  understate every rate and shift whenever the harness writes a result line.
- Storage is one SQLite file with a schema-version gate that wipes and re-indexes instead of
  a migration engine: the index is derived and disposable, so migrations would be machinery
  more expensive than the data they protect.
- There is no authentication, no incremental indexing, no datepicker and no mobile layout;
  each is named with its reason in docs/DESIGN.md, and a full re-index of the measured
  168 stream corpus takes about 4 s, which is why re-indexing is the shipped answer to most
  of them.
