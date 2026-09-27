# AI-NOTES — what the machine did here, and what it caught

*Written by the author, for the reviewer who asks where the machine stops and the author
begins. Short version: the machine wrote most of the code. It did not write a decision.
Every cut in the spec, and every number the spec quotes, was made or measured by the
author — see the last section for what that means in practice.*

## 0. The setup, named

This project was designed, planned and built by a locally hosted stack, on one machine,
with no request leaving it. Later it was worked on with a hosted assistant as well. Both
are true, the split is not tidy, and the interesting part is where the line actually falls
— so it is drawn here rather than summarised.

**Built locally:** the specification through rev 4, the data model, the detection model and
every decision in it up to that point. `StampGuardDetector`'s attribution — the only real
domain reasoning in the program — is the local stack's; the hosted assistant's only edit to it
in rev 5 was mechanical (one new constructor argument, `null`, where a `Finding` is built). The
cut list, the measured numbers through rev 4 and the plane mapping are local decisions.

*An earlier version of this paragraph said `inspector.ingest` had no commit from the hosted
assistant at all. Rev 5 made that false, and the paragraph is corrected rather than left to
describe a tree that moved: see the fourth round below.*

**Where the hosted assistant worked, in four rounds:**

- **A review pass.** It read the finished codebase and reported defects. Every one of the
  fifteen fixes in §3 was then written by the local stack, and two of them corrected the
  reviewer: the severity it reported was understated in one case, and its instruction for
  the foreign keys could not have worked at all.
- **A documentation pass.** The `@Operation`/`@Parameter`/`@Schema` descriptions on the five
  routes, the two `OpenApiDocumentTest` cases that fail when a parameter arrives without a
  sentence, the README's screenshots and reading order, and the restructuring of §3.
- **A round of work on the read side, which did change behaviour.** URL-addressable state
  for the filters, the sort, the page and the code drill-down; the numbered pager; the
  collapsible filter rail; the error-code breakdown on the board and the drill-down it
  opens; the detail panel turned from a modal into a real side panel. Plus correctness
  fixes it found by running the tool on a real corpus: an unmapped error code landing on
  the wrong plane, a filter facet that could not select its own `unknown` bucket, and a
  confidence label that described a search which never ran.
- **Rev 5: the inspector as a judge, and a JPA store** (DESIGN.md's rev-5 note lists every
  section). This round did reach into `inspector.ingest`, and changed behaviour there: it fixed
  the fatal-turn parse (the ingestor read a field DSH does not write, so every real fatal turn
  had been stored without a code), kept `request/context.provider`, and split the shell
  analyzer's mutating verb into write forms. It added the edit-miss and shell-edit detectors, the
  harness timeline, the judge with its statistics, the breakdown and the finding context, the
  headless report, parallel ingest, and moved the store from `JdbcTemplate` to Spring Data JPA —
  a reversal of rev 4's argument that the owner asked for, recorded in DESIGN.md §4.2 with what it
  cost rather than silently. The frontend half ran as a separate delegated lane. The owner set the
  direction and approved it; the design and the code in this round are the hosted assistant's,
  and the commits say so.

**This is checkable rather than asserted.** Every commit that hosted assistance touched
carries a `Co-Authored-By` trailer, so the split is a query rather than a claim:

```
git log --format='%H %s' --grep='Co-Authored-By: Claude'
```

No count is written here on purpose. An earlier draft said "sixteen of them", and the commit
that would have corrected it to eighteen carried a trailer of its own — a number that goes
stale as it is written is the same defect as the prose this section is about, only faster.
Ask the repository.

An earlier version of this section claimed no hosted assistant had implemented a feature.
That was true when it was written and stopped being true a day later, which is exactly the
failure this document exists to catch — prose describing a state the code has moved past.
It is corrected rather than quietly deleted, because the correction is the more useful
record.

- **DSH** — the DeepSeek Harness, the agent runtime: tool dispatch, session logging, the
  permission model, the web UI on `:3080`. It is also the subject of this project. The
  session logs this dashboard indexes are DSH's own, written by the sessions that wrote
  the code.
- **The inference router** — the local inference layer in front of DSH, serving on `:8081`,
  with a control socket that swaps the served model mid-session.
- **Model:** served under one alias; the checkpoint in service is
  Qwen3.8-Flash-Next (NVFP4 quantisation), 262k context, reasoning effort `xhigh` by
  default. Worth noting for this project specifically: the session logs record the *alias*,
  not the checkpoint — `request/context.model` is that alias in all six of this
  repository's own sessions. A harness whose point is swapping models mid-session cannot
  currently tell you which one produced a given turn, which is a gap in the subject, not in
  the dashboard. *(Rev 5 closes the half of that gap the log allows: `request/context` also
  carries the provider route, and on this install the route names the model — DESIGN.md §3.5.
  The checkpoint behind a route is still not in the log.)*
- **Hardware:** RTX 5090 (32 GB), Ryzen 7 9800X3D, 96 GB RAM. The model served the agent on
  the same GPU the agent was running beside — which is the direct reason for the port and
  process rules in the checkout's `AGENTS.md`, and for the incident in §4: an agent that can
  kill its own inference server needs rules a cloud agent does not.

That makes this project self-referential on purpose. The tool measures the harness that
built it, and the failure rates on the dashboard are the failure rates of its own
construction — not a synthetic dataset chosen to make a dashboard look busy.

The evidence is retained. DSH keeps every session it ran, so the work behind this
repository is inspectable by the tool this repository is — and the ledger in §3 lists the
defects the loop in §2 caught, each reconstructible from the git history.

## 1. What the AI did, and what I decided

Decided and written by the author:

- The specification: `docs/DESIGN.md` — the questions the tool answers, the data model,
  the privacy boundary, the cut list, the measured numbers. The AI contributed to its
  drafting; the author reviewed it line by line through four revisions, and the final
  cuts are the author's.
- The stack. Spring Boot 4 on SQLite on 8091, Angular 22 on 4300, a proxy between them:
  each choice follows from one constraint (the tool runs beside the tool it observes, on
  a machine where the obvious ports are owned) and is argued in the spec, not asserted.
- Every exclusion. The session-detail waterfall, the admin/fleet plane, authentication,
  file watching, LLM-based analysis, the migration engine: each was cut by decision, each
  with a stated reason and an expiry condition, and none of them is "what the AI left out".

Drafted by the AI, reviewed by the author:

- The implementation plan (machine-local, not committed; the spec is the committed source
  of truth): the task split, the per-task acceptance criteria, and a "facts verified"
  section with the exact wire shapes. The author read it line by line before the first
  batch was dispatched and corrected the wire contract where it had drifted.

Implemented by the AI, in batches:

- The work was dispatched in task batches — the frontend history shows them as
  `Task 6` … `Task 10`, the backend history as `feat:`/`fix:` commits in dependency
  order — each batch sent with its plan section and the relevant spec sections pasted
  in, each returned with test output, and each reviewed against the spec before the next
  batch was sent.

## 2. The loop

The unit of work was:

```
spec → plan → implementer batch → review against spec → correction
```

Correction is a normal output of that loop, not a failure mode. The plan encodes the
spec's claims as testable acceptance criteria, so a mismatch surfaces as a failing test
or as a spec sentence the returned code cannot satisfy; fixing it is what the review
stage exists for. The cost of a correction round is one dispatch. The cost of a silent
deviation is a reviewer finding it first, and that is the only outcome the loop is
designed to prevent. The `fix:` commits in the history are the loop working, not the
loop failing.

The spec's job in the loop is to be *checkable*. Where a spec sentence is a feeling
rather than a predicate, a batch cannot verify against it, and a deviation through that
sentence is invisible until a human looks. The ledger below clusters where the spec was
checkable and the implementer was not — and the loop caught the defects there.

## 3. The ledger

**Headline: automated tests alone would have caught ~4 of the 15 backend defects.** The
rest were caught by the review loop (reading returned code against the spec), by running
the shipped artifact, or by reasoning about the fixture — roughly descending by what
they would have cost.

The launcher table at the end of this section is a class of its own, and its headline is
worse: **not one of those defects was caught by any automated check**, because every check
ran in an environment that flattered the code — stdin detached, no controlling terminal,
signals force-ignored for background jobs. Each was preceded by a green run.

### Backend

| Defect | What caught it | What it would have cost |
|---|---|---|
| Jackson 3's `JacksonException` is unchecked, so every `catch (IOException)` the plan wrote around the parser was dead code | Compilation, after the shape had propagated through the whole plan | Parse errors vanishing into the wrong handler; a spec sentence about error shape that was true in the plan and false in the code |
| The plan's own redirect regex classified `2>/dev/null` as a mutating command | Fixture test | Every command that suppresses stderr read as a violation's cause — the stamp-guard detector lying systematically about the most common shell habit |
| The spec's own example, `python -c "open(...).write(...)"`, was not recognised as mutating | Fixture test | A whole class of direct writes missed — the class the detector exists for |
| `"…".formatted(a) + "%d"` left a literal `%d` in the message | Review against the spec | A malformed line at the one place an operator reads to debug the indexer |
| The boundary test was vacuous: the plan's fixture put the cause *inside* the window, so the open-interval rule was never exercised | Review; proved by widening the interval — two tests then failed | The load-bearing rule of cause attribution (open interval, not closed) shipping untested; the first real corpus finding it as a wrong attribution |
| Cause selection checked verb class before the clock, contradicting §5.3; the first fix was still wrong | Caught twice | `git restore` before a mutation misattributed to the mutation — the case the spec named by name |
| Negative TTFT from a message-timestamp anchor after its own chunk timings | Reasoning about the fixture; no test existed | A chart with sub-zero bars on real data, labelled as a measurement |
| `occurred_at` had no event→row path (carried on ingest records) | Review against the spec's data model | The timestamp column — what the time filter joins on — populated by field-order accident |
| A `ShellEvidence` field declared on a detector | The project's own privacy lint test, on the author's own new code | The boundary the tool is defined by, broken by the code meant to enforce it — invisible to every other test |
| `tool_call.name NOT NULL` crashed on the real corpus; the fixtures never exercised it | Running the indexer against the real corpus | The first real run dying on a constraint the fixture never filled — "clone and run" broken by one column |
| Sessions counted as streams: tile 168 vs rail 165 | Visible in a child's own pasted JSON; `count(distinct id)` | Two numbers on one screen disagreeing by a count only a careful reviewer would notice — with every downstream comparison built on the wrong one |
| The packaged jar could not boot from a fresh clone: default `var/inspector.sqlite`, and sqlite-jdbc does not create parent dirs. **137 green tests shipped it** — every test overrode the JDBC URL | Running the shipped artifact | The deliverable's own quickstart failing on a clean machine with a message pointing at the wrong layer |
| Re-index against an old schema → HTTP 500 | Review of the version gate; `meta.schema_version` check now wipes and re-indexes in one transaction | Any schema evolution between two runs of one clone: the Index button returning a stack trace |
| `@SpringBootTest` runs `ApplicationRunner` beans under Boot 4, against the author's belief that it does not | The implementer verified it empirically; every test context now passes `--no-index` | Every store-touching test running the indexer against whatever the classpath holds — slow, flaky, machine-dependent |
| `/api/cohorts` accepted the shared filter parameters and ignored them: the store sent them, the client encoded them, the controller did not declare them, the repository had no `WHERE` at all. Concealed by an HTTP test double that answers whatever it is handed, and by the spec asserting the contract was shared | Running the shipped artifact: filtering to one of five models returned byte-identical totals to the unfiltered read, and a value that exists nowhere was answered with HTTP 200 | A comparison screen showing unfiltered rates under any filter — the cohorts view answering a question about this window with the whole corpus, with no error and no warning |
| The judge's verdicts moved with its estimator four times. A pooled Pearson φ and then a sandwich φ on the normal quantile both called failed edits "worse" after the 27B swap (the first also called shell edits "better"). An uncapped t then called file-not-found "worse" on one baseline session. At six sessions a side the normal quantile gave a false verdict in 8–15% of simulated no-difference comparisons, the uncapped t in 7–12%, against a nominal 5% | Two adversarial re-verifies that simulated the estimator under no difference. The unit tests checked the arithmetic each estimator was written to do, and passed every time | The harness loop's first lesson, "the swap made edits worse", written into the docs and the agent's memory as a result, on evidence that cannot carry it |
| The first t-quantile fix gave zero degrees of freedom to every side whose findings sat in one session, a single finding included. A code that fell from twenty to none could be called "better"; one that fell to one never could | Looking at the rendered judge on the fixtures: intervals of [0.00–1.7×10⁹] on single-finding rows | A judge unable to confirm a fix in exactly the rows where the fix worked |
| A quoted-string regex that recursed once per character overflowed an ingest worker's stack on one long command in the real corpus. It happened on some runs and not others, depending on how large the JIT made the frames | A corpus re-measure after an unrelated change. The previous run of the same code had passed | "Clone and index" failing intermittently with a StackOverflowError, on exactly the long commands the shell detectors exist for |
| Once the overflow was fixed, the path pattern behind it backtracked quadratically on a long token, and cubically when the token held `~` or `+`. A 1 MB payload stalled a worker for 20 s, and the branch's own regression test took 220 s | A re-verify that timed hostile inputs. The suite's running time had said so for two rounds, and nobody read it | Indexing that stalls instead of crashing. That is slower to notice, and the index waits on its workers in order |
### The external review pass

A hosted assistant read the finished codebase and reported defects; the fixes were written
by the local stack (§0). Fifteen landed. The table is the scannable form — one line each,
with the commit that carries the full reasoning in its body. The account under each row is
the working one, kept because in several cases the interesting part is not the defect but
what the fix exposed on the way.

| # | Defect | Fix |
|---|---|---|
| T09 | A re-index merged into the index instead of replacing it, so a deleted session kept being served | `7d40b9d` |
| T10 | The only `tool_call` index served no query, while every join key was unindexed | `08061b4` |
| T01 | `api` and `store` imported each other; a 153-line SQL builder lived in the web package | `077a09f` |
| T02 | No layer between HTTP and SQL: the controllers owned the rate maths, the sort parser and the chart merge | `4348f32` |
| T06 | `POST /api/index/run` had no lock, so two runs interleaved across six tables | `e967246` |
| T11 | Every overview response shipped every session id — 6,812 of 8,997 bytes, read by nothing | `00bbfb7` |
| T03 | The JSON contract and the `ResultSet` mappers were the same types | `5fc18b8` |
| T05 | `schema.sql` declared no foreign keys while two comments justified their ordering by that enforcement | `8f32a7c` |
| T04 | The 634-line fixture generator shipped inside the deployable jar | `5e1ddf5` |
| T07 | The `Detector` contract was honoured for one implementation; production parsed with a mapper the tests never saw | `6375836` |
| T08 | The generated spec described a request no route serves, and the actuator key asserted its own default | `cb970a0` |
| T12 | One rejected filter value answered with 13,726 bytes, the allowed set twice over | `299f110` |
| T13 | `sort` folded its direction but not its key, so `TIME:desc` was a 400 and `time:DESC` was not | `f6e0dce` |
| T15 | The plane-filtered findings sort built a temp B-tree the one time index could not serve | `01c0849` |
| T14 | DESIGN.md §6 enumerated two indexes the code had already retired | `68f0155` |

Two of these were reported with the severity understated, and one review instruction was
wrong: the plan for the foreign keys could not have worked as written, because the reset only
deletes rows and SQLite has no `ALTER TABLE ADD CONSTRAINT`. Following it would have produced
a green suite and a comment that was still false. That is the argument for the loop below
rather than for the reviewer.

<details><summary><b>T09</b> — A re-index merged into the index instead of replacing it, so a deleted session kept being served (<code>7d40b9d</code>)</summary>

**What it was.** A re-index merged into the index instead of replacing it: `writeStream` deleted the stream it was about to write and nothing else, so a session removed from the corpus kept every row it had and kept being served

**What caught it.** Found by the external review pass (§0), then confirmed by re-running its reproduction against the packaged jar — `pruned` is now on the wire and on the toolbar line

**What it would have cost.** One screen giving one question two answers on the tool's only mutating action: the run's summary line reporting 7 findings while the tiles beside it reported 9, the difference being a session no longer on disk

</details>

<details><summary><b>T10</b> — The only `tool_call` index served no query, while every join key was unindexed (<code>08061b4</code>)</summary>

**What it was.** The single index on `tool_call` covered `(name, error_code)` — a pair no query filters or joins on — while the `(session_id, source_file, seq)` every query does join on was unindexed; the default findings sort ran in a temp b-tree because the one time index led with `plane` and had an unconstrained `category` behind it

**What caught it.** Found by the external review pass (§0), read out of `EXPLAIN QUERY PLAN` before and after; the plans are now asserted by `QueryPlanTest` so the shape cannot quietly rot again

**What it would have cost.** Every finding-detail request scanning all 17,244 tool-call rows, and every findings page sorting the whole table to show twenty rows — on a read API whose entire argument for existing is being fast enough to look at

</details>

<details><summary><b>T01</b> — `api` and `store` imported each other; a 153-line SQL builder lived in the web package (<code>077a09f</code>)</summary>

**What it was.** `inspector.api` and `inspector.store` imported each other — the store reached into the web package for the filter contract and the wire shapes, the controllers reached back for the repositories — so the layer names described nothing and a 153-line SQL `WHERE` builder lived in the web package

**What caught it.** Found by the external review pass (§0); the direction is now a build failure — `PackageCycleTest` reads the compiled constant pool, and was checked against a deliberate violation so it is known to fail with the offending class named

**What it would have cost.** Not a crash but a ceiling: neither package readable, movable or testable alone, and the one rule that keeps SQL out of the controller layer unenforceable — every later refactor pays for it, and `store` reading `api` is the shape that turns a small tool into the thing that cannot be extended

</details>

<details><summary><b>T02</b> — No layer between HTTP and SQL: the controllers owned the rate maths, the sort parser and the chart merge (<code>4348f32</code>)</summary>

**What it was.** There was no layer between HTTP and SQL: `CohortsController` computed the per-1,000 rates, chose the baseline and generated the basis-note prose, `FindingsController` parsed the sort syntax, `OverviewController` merged the chart series — so 8 of 28 test classes booted a Spring application to check arithmetic, and the project had not one `@WebMvcTest` slice

**What caught it.** Found by the external review pass (§0); three `inspector.insight` services now own it, the rate/baseline/note/sort cases run as plain JUnit against a stubbed repository, and `PackageCycleTest` forbids `insight → api` so the 404 cannot creep back down

**What it would have cost.** Not a wrong number but a slow, dim one: every check of `Math.round` cost a context boot and an indexed corpus, so the cheapest possible regression (a flipped delta sign) had the most expensive possible test around it — and the arithmetic was only reachable through HTTP, which is how a domain rule ends up untested rather than tested badly

</details>

<details><summary><b>T06</b> — `POST /api/index/run` had no lock, so two runs interleaved across six tables (<code>e967246</code>)</summary>

**What it was.** `POST /api/index/run` wiped and rewrote six tables with no lock, on a per-stream transaction boundary, so a second POST — another tab, a curl, a startup run racing a manual one — interleaved with the first, and since the prune landed its prune could delete rows the first run was still writing

**What caught it.** Found by the external review pass (§0); a lock held for the whole run now answers the second request with 409, and writing it exposed a second hole: the startup resets run *before* the index call, so a literal reading of the fix left "empty the tables" outside the guard, a moment for a boot to wipe rows a run was filling — the guard covers the whole decide-and-rebuild sequence now

**What it would have cost.** An index whose contents depended on thread order, reachable with one impatient click in a second tab, and an application that would have aborted its own startup had that click beaten the startup runner to the lock

</details>

<details><summary><b>T11</b> — Every overview response shipped every session id — 6,812 of 8,997 bytes, read by nothing (<code>00bbfb7</code>)</summary>

**What it was.** Every `/api/overview` response carried the id of every session in the index — 6,812 of its 8,997 bytes on the author's corpus, and a list no control reads, since the rail has four facets

**What caught it.** Found by the external review pass (§0), then **split by measuring it**: the seven `select distinct` queries blamed for the cost turn out to be 0.9 ms of an 18.6 ms request, so the cache the task proposed was dropped and only the unbounded list was cut from the wire (`dto.VocabularyOptions`: six bounded lists, validated against the seventh)

**What it would have cost.** A dashboard that grew heavier in proportion to how much it had been used, quietly — 4.1× the bytes per load, all of it ids, rising with every session ever indexed, on a screen whose tiles never changed

</details>

<details><summary><b>T03</b> — The JSON contract and the `ResultSet` mappers were the same types (<code>5fc18b8</code>)</summary>

**What it was.** `FindingRepository` mapped a `ResultSet` straight into `FindingDto` and `OverviewRepository` returned a nested DTO record, so the JSON contract and the SQL row mappers were the same types

**What caught it.** Found by the external review pass (§0), then made unfalsifiable: `PackageCycleTest`'s new rule reads compiled constant pools, so even an inline fully-qualified import across the boundary fails the build. The blast radius was measured on both sides of the change — adding one wire field broke `FindingsService:104` afterwards and `FindingRepository:95`, a SQL mapper, before it

**What it would have cost.** Every API change reaching into SQL: answering "the UI needs one more field" meant editing a query, so the two shapes could drift apart by the amount of whoever happened to notice the compile error — and the fix's own grep was passing on a javadoc exception until it was cleaned

</details>

<details><summary><b>T05</b> — `schema.sql` declared no foreign keys while two comments justified their ordering by that enforcement (<code>8f32a7c</code>)</summary>

**What it was.** `schema.sql` declared no foreign keys at all while the JDBC URL set `foreign_keys=on` and two `IndexWriter` comments justified their delete ordering by that enforcement — and the planned fix (declare them, bump `SCHEMA_VERSION`) could not have worked: the reset only deleted rows, `CREATE TABLE IF NOT EXISTS` skips a table that exists, and SQLite has no `ALTER TABLE ADD CONSTRAINT`

**What caught it.** Found by the external review pass (§0); the second half was caught mid-task by arithmetic — T10 had already spent the `1 → 2` bump, so a committed-but-constraintless version 2 database existed and the schema change had nowhere to land. Enforcement itself was then pinned by tests rather than by reading: two connections borrowed at once both report `pragma foreign_keys = 1`, and one connection with the pragma off accepts the orphan row the same schema rejects with it on

**What it would have cost.** The comments would have stayed false, which costs the credibility of the redaction and stream-key comments next to them that are true. Worse, shipping the declared keys alone would have made the claim true only on fresh clones: an existing `inspector.sqlite` keeps its keyless tables forever while the DDL file describes constraints it does not have — a fresh green `mvn test` and a reset log line saying the migration happened

</details>

<details><summary><b>T04</b> — The 634-line fixture generator shipped inside the deployable jar (<code>5e1ddf5</code>)</summary>

**What it was.** `FixtureGenerator` — 634 lines, the largest class in the project, and a development tool nothing at runtime calls — shipped inside the deployable jar, and DESIGN.md §12's tree claimed the committed corpus lived at `src/main/resources/fixtures/`, a path that does not exist

**What caught it.** Found by the external review pass (§0); the path was found while checking step 5, and the byte-identity the corpus's whole provenance claim rests on was checked with a `sha256sum` list over all 15 files before and after a regeneration rather than with `git status`, which cannot see an untracked difference

**What it would have cost.** A reviewer opening the artifact finds the biggest thing in it is a generator, which reads as carelessness about what ships — and anyone trying to verify "these fixtures are generated, not hand-edited" had a documented path to a directory that isn't there, with no command named anywhere outside the pom

</details>

<details><summary><b>T07</b> — The `Detector` contract was honoured for one implementation; production parsed with a mapper the tests never saw (<code>6375836</code>)</summary>

**What it was.** `ErrorPlaneDetector` asked `StampGuardDetector` by concrete type for the tool codes it owns, so the `Detector` contract that says "adding a detector is one class plus a bean declaration" was true of one implementation only; and `ShellAnalyzer` carried a second, no-arg constructor, which is the one Spring reaches for when no constructor is annotated — meaning the running application parsed shell commands with an `ObjectMapper` the class had built itself, and the injected mapper was reachable only from a test

**What caught it.** Found by the review pass, then pinned instead of asserted. The skip set is a `List<Detector>` union with stub-detector cases and the compiled class has zero references to `StampGuard` in its constant pool; the mapper claim was tested by restoring the two-constructor version from `HEAD`, against which the new context assertion fails (`Tests run: 5, Failures: 1`) and passes again once one constructor is left. Reaching the wiring also turned up three test harnesses handing the error detector a list the application never injects, and one building a throwaway second copy of a detector already in the pipeline

**What it would have cost.** The duplicate-finding half was the silent one: the corpus-level duplicate check only catches it if the fixture corpus happens to contain the newly owned code, so the one-owner-per-error invariant would have broken on the author's own corpus with a green suite. The other half cost less loudly — the object the tests described was not the object production used, which is the arrangement where a parsing difference shows up first in real data

</details>

<details><summary><b>T08</b> — The generated spec described a request no route serves, and the actuator key asserted its own default (<code>cb970a0</code>)</summary>

**What it was.** The generated API documentation described a request the application cannot answer: `InsightFilter` binds as a model attribute, so springdoc rendered it as a single query parameter named `filter` whose schema was the record, on three of the five routes. Next to it sat the more uncomfortable shape: `management.endpoints.web.exposure.include: health` in `application.yml`, asserted by "env is 404" — except Boot's default exposure *is* health alone, so a misspelled key keeps every one of those assertions passing while the line does nothing

**What caught it.** Found by the review pass as a "check what the generator makes of `@ModelAttribute`" step, then measured on both halves. Before the fix the document printed `filter in=query type=#/components/schemas/InsightFilter`; the fix is `@ParameterObject` on all three bindings — Spring's own version of that annotation is gone from Framework 7, so it is the one springdoc ships — and the new document test was checked by deleting the annotation from a controller and watching it fail, then restoring the file byte-identically. The exposure hazard bought two test classes instead of one: the shipped 404s plus the three config keys read back from the `Environment` under their canonical names, and a second context that opens another endpoint through the same property to prove the property is honoured

**What it would have cost.** A published spec that points a client at a URL no route serves is worse than no spec at all: the first integration a reviewer attempts fails, and the wrong thing is the document. The config half is quieter — an unread line that reads as the §4.1 boundary being drawn, and in the same file a readiness widening that is the only one of those three lines moving away from a default, so a dead key there means "ready" slides back to meaning "the process started", with nothing anywhere saying so

</details>

<details><summary><b>T12</b> — One rejected filter value answered with 13,726 bytes, the allowed set twice over (<code>299f110</code>)</summary>

**What it was.** `?session=<unknown>` answered one wrong value with **13,726 bytes**: `UnknownFilterValueException` joined the allowed set into its message and the handler then put the same list on the problem as the `allowed` property, while the rail's own sentence builder `join`ed all 165 ids into a bar one line tall

**What caught it.** Found while measuring 400 bodies for T07's page-size cap, then measured on both sides of the wire — `detail` alone was 6,727 characters of that body. The message stops at the filter and the value now, and the builder caps its list at six values plus a count, a ceiling read off the measured vocabularies (schemas 2, presets 3, detectors 4, models 5, the `sort` key list exactly 6) so nothing any screen can send is ever truncated. Reproduced live against a scratch copy of the fixture corpus: pick a schema, delete the streams that carried it, re-index — the bar reads `Unknown filter value: 'V3' is not a valid schema. Valid: V0` over the previous data, which stays put

**What it would have cost.** The byte count is the cheap half, paid by whoever reads a log. The bar was the expensive one: a repair message that lists 165 things you could have typed is not a repair message, and it is the one element that appears only when something has already gone wrong — the moment the interface cannot afford to be unreadable

</details>

<details><summary><b>T13</b> — `sort` folded its direction but not its key, so `TIME:desc` was a 400 and `time:DESC` was not (<code>f6e0dce</code>)</summary>

**What it was.** `?sort=time:DESC` was accepted and `?sort=TIME:desc` was a 400: the direction was lowercased before its check while the key was looked up raw. The case had been written down as intent, with a comment arguing that folding the key "would let two spellings of a sort mean the same thing in two different places". The list that 400 prints came from a `Map.of` keySet, whose order the JDK leaves to a hash

**What caught it.** Found reading the sort whitelist for T07's page-size cap, and pinned on both halves: the four spellings of the token now answer with the same ids (`[9, 8, 7]`) on the running application, so the fold is an acceptance change rather than a change of meaning; the offender is quoted as sent; and the allowed keys are asserted `containsExactly` in the written order instead of `containsExactlyInAnyOrder`, which could not have caught a reordering. `?plane=guard` is still a 400 — data values are case-sensitive for a different reason, and the new pair of tests names which rule is which

**What it would have cost.** The trap was aimed at the one reader most likely to spring it: someone with a terminal checking whether the API does what the README claims, handed a rejection for a spelling the same method accepts five characters later, with no hint which half of the token they mistyped. The hash-ordered list cost less and bit quieter — an error message whose word order can change under an unrelated edit is a message nobody can quote back

</details>

<details><summary><b>T15</b> — The plane-filtered findings sort built a temp B-tree the one time index could not serve (<code>01c0849</code>)</summary>

**What it was.** `idx_finding_occurred (plane, category, occurred_at)` could not serve the plane-filtered findings sort. Nothing filters findings by `category`, so the order remaining after the equality the query carries was by a column no query constrains, and the planner answered the findings page with `USE TEMP B-TREE FOR ORDER BY` — sorting the filtered rows per request, paid for by an index that did not prevent it

**What caught it.** Found reading the index list after T08, named by SQLite's own optimizer overview (§10.1 *Partial ORDER BY via Index*: a satisfied prefix with an unsatisfied later term means block sorting), then measured as a plan before and after on the author's corpus — the sorter line disappeared, the median went 0.067 ms → 0.025 ms over 300 runs, and the index count stayed the same. The first "after" measurement showed no change whatsoever and read as a schema-version finding; it was my own jar still carrying the old `schema.sql`, caught by `unzip -p … BOOT-INF/classes/schema.sql`

**What it would have cost.** 0.042 ms at 389 findings, so nothing a screen can show — the cost was the standing claim, an index whose declared column order advertises a sort it cannot deliver, which is precisely what a later reader trusts while choosing the next one. Plus write cost on every re-index for a middle column no query uses. The measurement that looked like a null result is the transferable part: a stale artifact reports a clean negative

</details>

<details><summary><b>T14</b> — DESIGN.md §6 enumerated two indexes the code had already retired (<code>68f0155</code>)</summary>

**What it was.** DESIGN.md §6 enumerated indexes the code had stopped shipping: `tool_call(name, error_code)` was retired by T10's own commit and `finding(plane, category, occurred_at)` by T15's, and neither left a mark on the paragraph that listed them. Alongside it, the four foreign keys whose child-first delete order the writer's comments depend on had no assertion on their declared action

**What caught it.** Found by the hygiene pass while assembling the schema-version history: writing that table meant reading the DDL and the document side by side, and the two disagreed. The FK half arrived already half-covered — `aSessionStillHoldingFindingsCannotBeDeleted` would have failed under `ON DELETE CASCADE` — so what was missing was the declared rule, which `ON UPDATE CASCADE` would have changed in nothing observable; the new assertion runs both actions over all four keys and was checked by typing `cascade` into the DDL and watching it fail (`expected: "NO ACTION" but was: "CASCADE"`)

**What it would have cost.** A reviewer who trusts §6 goes looking for an index that is not there and decides the document is decorative — which is how documents stop being read and settled decisions start getting re-litigated. This is the second instance of the shape in this repository (T04 found §12's tree naming a fixtures directory that never existed), and the pattern is the same: a commit changed an artifact while the prose describing it was nobody's file to touch

</details>

### Frontend

| Defect | What caught it | What it would have cost |
|---|---|---|
| Material's theme symbols were wrong for 22.1.6 (`primary`/`tertiary`/`theme-type`, not `theme-t`/`theme-c`/`kind`) | Type-check on the theme build | The first `ng build` failing; a reviewer unable to reach any screenshot |
| A Sass comma-list inside a map needed parentheses | Build failure | Same: the dev server not coming up at all |
| `expectOne('/url')` does not match query params in this Angular version | Fresh spec failing | A test convention passing vacuously in the findings and cohorts specs — asserting requests that were never matched |
| `@use 'theme'` from a component re-emitted the entire Material theme into every component stylesheet | The 8 kB per-component budget warning | Every route chunk carrying the whole theme; the initial bundle hundreds of kB heavier with no visible change |
| DecimalPipe pulled the locale table into the initial bundle (+10 kB) | Bundle-size budget check | Ten kB for one two-digit number on one screen, paid by every first load |
| `provideAnimations()` not needed and left out | Review | A dead provider in the app config — small, but the line that becomes load-bearing folklore later |
| The rail became a plain CSS `aside` instead of the spec table's `mat-sidenav` | Measured-cost review; documented as a deviation in DESIGN.md §8 | A state machine for a state that never occurs: open/closed, pinning, overlay — none of which the design has |
| `$!` returned wrapper pids twice, once leaving a JVM holding 8091 for 27 minutes | Verified empirically with `ss -ltnp` | run.sh's "Ctrl-C stops both" being false: the port stays held, the next run fails with address-in-use, and the cause is a process the operator cannot name |
| The toolbar action carried `mat-flat-button` with no button module imported: the attribute was inert, and Chrome painted a native light button on a dark toolbar. Concealed by the dark theme really being loaded — a review that assumed Material was doing the work saw a toolbar that looked right | The author, looking at the running page | The product's one verb, its primary action, advertised as a broken control on first paint |
| The toolbar action's label and disabled state keyed off the store's global request counter: any filter change made it announce "Indexing…" while it was merely reloading a table. Concealed by correctness under the one flow ever exercised manually — the flow that never filters while indexing | The author, changing a filter while a table reload was in flight | A toolbar that lies about its own work on every filter change — while the signal that describes its own work (`indexing`) already existed in the store, unused |
| The findings pager could never reach the last page: the full result size was compared against a multiple of the page size, while `canNext` used the current slice's length — the two disagreed exactly on a partial last slice. Concealed by tests with two full pages, where the total is a multiple of the size and both formulas agree | A test with a partial last page | The last slice of every findings list — the most recent findings, the rows the tool exists to surface — unreachable from the UI |
| ECharts rendered its default white tooltip on a dark dashboard: the chart option was built next to the theme without spreading `CHART_BASE`, so the theme applied at the chart root silently lost to an option constructed beside it. Nothing was wrong until a user hovered | The author, hovering a bar on the running dashboard | The one interaction a chart exists for — reading a value — answering with a white box on a dark canvas, on the default screen, every hover |

### Launcher and verification

The defects that only a human running it could show, because the agent's own environment
detaches stdin, has no controlling terminal, and force-ignores some signals for background
jobs. Every one of these was preceded by a green check.

| Defect | What caught it | What it would have cost |
|---|---|---|
| The database never recorded which corpus its rows came from, so a run configured for the fixtures served the real corpus's 389 findings | A human running both modes on one database file, after the agent had called the launcher verified | The first screen a reviewer opens describing data that run never read — and the mirror image, an unexplained 9 findings, an hour earlier |
| An index of unknown provenance was trusted rather than reset; "rows present" was treated as "rows from this corpus" | Writing the reset's complement tests | The same wrong screen from a database that predates the provenance row, which is every database written before this fix |
| The port opened ~0.8 s before indexing finished, so the first read of a 168-stream corpus showed 37 of 165 sessions with nothing on screen saying "still working" | A check that compared the served counts against the indexed ones instead of only checking that the port answered | The tool under-reporting its own findings silently on every cold start, which reads as "it does not find much" |
| `ng serve` puts a keypress listener on stdin; a background process group that reads the controlling terminal is stopped with SIGTTIN, so 4300 never bound | Reproducing the human's exact invocation under a pty, after agent-launched runs had passed twice | The reviewer's first command failing with a refused connection, a clean exit, and an announced URL that was never served |
| The CLI's once-per-machine analytics prompt is unreadable with stdin detached ("User force closed the prompt"), and `cli.analytics: false` in `angular.json` does not suppress the question | The human's terminal, where the prompt was answerable at all | A crash waiting on any machine that has never answered that prompt — which is every reviewer's machine |
| A trap set for INT never ran while the script was blocked in `wait -n` | Sending the signal the way a terminal does. The first attempt could not fail: a background job in a shell without job control has SIGINT force-ignored | Ctrl-C leaving both processes holding their ports, and the next start failing on address-in-use with no visible cause |
| An orchestration command SIGTERMed the inference server serving its own session, because the pid came from grepping the whole `ss` listing rather than the target port's line | Reading the pid back after the fact | Its own runtime, restarted mid-task. The rule is not "be careful": a pid must come from a filtered query, and a machine running the agent's model beside the build has no margin for that mistake |

The five defects added in this revision repeat the launcher table's shape: each was preceded by a green check that measured the presence of the machinery — a theme that loaded, a 200 that answered, a page that rendered, a control that existed — while the behaviour it was supposed to prove sat unmeasured next to it. That is the class the green conceals: the check passes about a narrower claim than the one it was written for, and the green is real, which is exactly why it was believed.

## 4. What I would change

- Pin the wire contract *before* the plan, not inside it. The plan carried the API
  shapes, and the shapes drifted (`null` on the wire where the type said `string`); a
  committed artifact the plan references would move that failure left.
- Make the spec state, per defect class, *how it will be caught*. Several ledger rows
  share one shape — "the spec had the rule, the fixture did not exercise it" — and a
  one-line verification plan per spec section would have caught the vacuous boundary
  test in review rather than after.
- Generate the fixture corpus from the same code path as the indexer's version handling.
  The spec's "two synthetic versions" claim is not what the shipped generator produces —
  one configured property, `version_inferred = 1` for all — and the two-corpus,
  two-versions index exists only in the test fixtures. The cohorts screen's one-row
  banner is the honest rendering of that gap, but a spec sentence should not out-run its
  generator. (Closed in rev 5, from the other side: the demo imports
  `backend/fixtures/harness-timeline.yml`, which gives the fixture sessions two synthetic
  versions by start time, and the judge screenshot is taken on exactly that.)
- Brief the implementer on the run.sh process-group problem as a *known hazard*, with
  the `ss -ltnp` evidence, instead of letting it rediscover it. It is the one defect
  where the machine's own job control was the trap, and the environment is exactly what
  a prompt should brief.
- Re-run anything that starts a process **under a controlling terminal**, and compare
  served counts rather than port answers. Four separate defects hid in the difference
  between a harness-launched process and a human's shell: SIGTTIN stopping the dev server,
  an analytics prompt with no stdin to answer, a trap that cannot fire inside `wait`, and a
  readiness check that measured the port instead of the data. The green runs were not wrong;
  they were about a launcher nobody uses.

- **Do not rebuild an artifact a live process is reading.** During the architecture pass the
  agent ran `mvn -DskipTests package` while the owner's backend was serving from that jar. The
  build rewrote the file in place — same inode, and the JVM's two open fds on it carried no
  `(deleted)` marker — so the running process started loading classes from a zip whose central
  directory no longer matched its contents. The dashboard kept working, because the busiest
  endpoint had been hammered all afternoon and every class on its path was already resident; a
  cold path died instead, and the tell was absurd: a `500` with `Content-Length: 0` and
  `Connection: close`, thrown before any handler, for one value of the `Host` header only, on a
  request that should have answered `400`. The same request against a freshly started JVM built
  from the same source answered `200`. Diagnosis took three steps and included one wrong
  conclusion — the first reading blamed the rebuild, the second retracted that because the
  proxy path still returned `200`, and the retraction was the error. **A green hot path is not
  a health signal.** When a process may be half-loaded, sampling its busiest endpoint is
  precisely how an agent convinces itself nothing is broken. The fix is in `run.sh` now: it
  probes both ports before it builds or launches anything and refuses, naming the classpath
  hazard in the message. A request was used as the probe rather than `ss` or `lsof` because the
  script is meant to survive a reviewer on a platform that has only one of them, and `curl` is
  already a dependency. The alternative — build to a versioned name and swap — stayed unmade:
  four lines of probing close the case without introducing an artifact naming scheme to explain.
  Underlying all of it: a mutable artifact with a live consumer is a hazard no toolchain warns
  about, so the guard belongs in the script that creates the situation.

## 5. What is not here

Nothing was pushed. The repository has no remote, the commits are local, and publishing
is the author's decision, not the machine's.

Every number in the spec was re-measured against the corpus it describes, not copied
from an earlier draft or a plan. Where a draft number and the measurement disagreed, the
measurement won and the draft was marked retired in place — the mark is kept in the
record, not deleted, so a reviewer can see the correction happen and why.
