# AI-NOTES — what the machine did here, and what it caught

*Written by the author, for the reviewer who asks where the machine stops and the author
begins. Short version: the machine wrote most of the code. It did not write a decision.
Every cut in the spec, and every number the spec quotes, was made or measured by the
author — see the last section for what that means in practice.*

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

**Headline: automated tests alone would have caught ~4 of the 14 backend defects.** The
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
  generator.
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

## 5. What is not here

Nothing was pushed. The repository has no remote, the commits are local, and publishing
is the author's decision, not the machine's.

Every number in the spec was re-measured against the corpus it describes, not copied
from an earlier draft or a plan. Where a draft number and the measurement disagreed, the
measurement won and the draft was marked retired in place — the mark is kept in the
record, not deleted, so a reviewer can see the correction happen and why.
