import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, computed, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { Observable } from 'rxjs';
import { describeProblem, isIndexAlreadyRunning, isProblem, type ProblemDetail } from '../api/problem';
import { ApiService } from '../api/api.service';
import type {
  BreakdownRow, CohortPageDto, FindingContextDto, FindingDetailDto, FindingsPageDto, IndexSummaryDto,
  JudgeDto, OverviewDto, SortDir, SortField, Vocabulary,
} from '../api/types';
import { emptyFilters, FACET_KEYS, facetIsSet, withoutFacet, type FacetKey, type Filters } from './filters';
import { DEFAULT_GROUP_BY } from './url-state';

/**
 * The store that owns every fetch. §7's "one filter contract" lives here: overview,
 * findings and cohorts are all read through the same `filters` signal, so the tile board
 * and the table can never describe different queries. Components render these signals;
 * they never build params and never call the API.
 */
@Injectable({ providedIn: 'root' })
export class InsightsStore {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  // ---- state: one filters source, the data, and the request bookkeeping ----

  readonly filters = signal<Filters>(emptyFilters());

  readonly overview = signal<OverviewDto | null>(null);
  readonly findings = signal<FindingsPageDto | null>(null);
  readonly detail = signal<FindingDetailDto | null>(null);
  readonly context = signal<FindingContextDto | null>(null);
  /**
   * The finding whose sequence request failed, if the newest one did. Without it a failed
   * request left the panel saying "Loading…" for as long as it stayed open — a wait for an
   * answer that had already come back as an error.
   */
  readonly contextFailed = signal<number | null>(null);
  /** The finding whose detail request failed, by the same rule: the panel matches it by id. */
  readonly detailFailed = signal<number | null>(null);
  readonly cohorts = signal<CohortPageDto | null>(null);
  readonly judge = signal<JudgeDto | null>(null);
  readonly breakdown = signal<BreakdownRow[] | null>(null);
  readonly vocabulary = signal<Vocabulary | null>(null);
  readonly lastIndex = signal<IndexSummaryDto | null>(null);

  /**
   * A counter, not a boolean: two overlapping loads must not have the earlier completion
   * clear the later spinner.
   */
  readonly busy = signal(0);

  /**
   * Whether *this* action is running, as distinct from `busy` — the count of everything in
   * flight. The toolbar's one action used to key its label and disabled state off `busy`,
   * so a filter change three panes away made the primary button announce "Indexing…" while
   * it was merely reloading a table. A control may only describe its own work.
   */
  readonly indexing = signal(false);

  /**
   * Which axis the cohorts screen is currently grouping by, or null when that screen is not
   * open. The rail needs it because the grouping axis and a filter on the same facet are
   * mutually exclusive in effect: filter harness version V0 while grouping by harness version
   * and the comparison table has exactly one row, whose delta against itself is zero. Better to
   * dim the control and say why than to offer a button whose only product is a degenerate table.
   */
  readonly cohortAxis = signal<string | null>(null);

  // The cohorts screen's own state, fed from the URL like everything else: the axis, the
  // baseline the reader chose (null: the backend picks the busiest cohort) and the cohort the
  // judge compares against it (null: the only other one, when there is exactly one).
  readonly cohortGroupBy = signal<FacetKey>(DEFAULT_GROUP_BY);
  readonly cohortBaseline = signal<string | null>(null);
  readonly judgeCandidate = signal<string | null>(null);

  /**
   * Whether the Findings screen is open: the drill-downs (code, detector) narrow that screen
   * and no other, so what counts as "active" depends on it. Set and cleared by that screen,
   * as `cohortAxis` is by the cohorts screen.
   */
  readonly findingsOpen = signal(false);

  /** The human sentence from the last rejected request; a later success clears it. */
  readonly error = signal<string | null>(null);

  /**
   * The value the server rejected, when the newest request of a lane was refused over one named
   * filter — a stale baseline or detector from a shared link, or one a re-index removed. The bar
   * says what is wrong; the screen uses this to offer the one control that fixes it, since a first
   * load that fails leaves no table and so none of the table's controls.
   */
  readonly rejected = signal<{ lane: Lane; filter: string; value: string | null } | null>(null);

  /**
   * A request the server refused for a reason that is not a fault: an index run is already
   * going. It gets a bar of its own, in a colour that is not the error colour, because the one
   * thing this screen cannot afford is a red alert that means "nothing happened" — people start
   * dismissing the ones that do.
   */
  readonly notice = signal<string | null>(null);

  // Findings-only request state. The time range and the facets in `filters` above are
  // shared with overview and cohorts; these are not.
  //
  // `code` and `detector` used to be in the same sentence as plane and session: accepted by
  // the backend, driven by no screen, and dismissed here as residue. Each stopped being residue
  // when an overview panel made its count a control — the codes panel opens a code, the kinds
  // panel a detector — and the drill-down is this signal. Plane and session are still unwritten.
  readonly code = signal<string | null>(null);
  readonly detector = signal<string | null>(null);
  readonly sort = signal<{ field: SortField; dir: SortDir } | null>(null);
  readonly page = signal(0);
  readonly size = signal(20);

  // ---- derived ----

  readonly preset = computed(() => this.filters().presetId);

  /**
   * How many filters narrow what is on screen — the one count the rail's badge shows and the
   * rail's Clear button is enabled by, so the two cannot tell different stories. The drill-downs
   * count only on the Findings screen, the one they narrow; on the cohorts screen the facet it
   * groups by is left out of its requests (the rail dims it and says so), so it does not count
   * there. Counting either where it filters nothing announced a filter the screen was not applying,
   * and a Clear enabled by it would change nothing the reader can see.
   */
  readonly activeFilterCount = computed(() => {
    const f = this.filters();
    const inert = this.cohortAxis();
    let count = f.presetId === 'all' ? 0 : 1;
    for (const facet of FACET_KEYS) {
      if (facet !== inert && facetIsSet(f, facet)) { count += 1; }
    }
    if (this.findingsOpen()) {
      if (this.code()) { count += 1; }
      if (this.detector()) { count += 1; }
    }
    return count;
  });

  /**
   * Lanes whose newest request came back as an error, each with its own reason; a new request on
   * the lane clears it. The reason is kept per lane, not left to the error bar: the bar holds one
   * sentence and any later success clears it — on a fresh link the overview's 200 lands a few
   * milliseconds after the cohorts' 400, and a screen that said "the bar above says why" pointed
   * at nothing.
   */
  private readonly failures = signal<ReadonlyMap<Lane, string>>(new Map());

  /**
   * Whether the newest request of a lane failed. A screen waiting on a lane reads this to stop
   * saying "Loading…" once the answer has come back as an error — that is an answer.
   */
  failed(lane: Lane): boolean { return this.failures().has(lane); }

  /** Why the newest request of a lane failed, in one line — "400 · Unknown filter value: …". */
  failure(lane: Lane): string | null { return this.failures().get(lane) ?? null; }

  /**
   * Per-id cache for the lazy detail fetch, a plain Map for the session. Any reload
   * clears it: after the data is refetched, a cached detail is stale.
   */
  private detailCache = new Map<number, FindingDetailDto>();
  private contextCache = new Map<number, FindingContextDto>();

  /**
   * The newest request per screen lane. Two loads of one lane overlap all the time — two quick
   * page clicks, a facet change while the previous answer is still on the wire — and answers do
   * not come back in the order they were asked. Without this the slower, older answer landed
   * last, and the table showed page 2 under a URL and a pager that both said page 3.
   */
  private readonly latest = new Map<Lane, number>();

  // ---- bars ----

  /**
   * Dismiss the error bar. The bar shows whatever `error()` holds and the store is
   * the only writer: a later rejected request re-sets the signal and brings the bar
   * back, so dismissing can never hide a new failure permanently.
   */
  dismissError(): void { this.error.set(null); }

  /** Same rule as {@link dismissError}: the store is the only writer, so nothing stays hidden. */
  dismissNotice(): void { this.notice.set(null); }

  // ---- loads: all of them through the one filter state ----

  loadOverview(): void {
    this.invalidateDetails();
    this.track(this.api.overview(this.filters()), v => {
      this.overview.set(v);
      this.vocabulary.set(v.vocabulary);
    }, { lane: 'overview' });
  }

  loadFindings(): void {
    this.invalidateDetails();
    // FindingsRequest's optional fields are `| undefined`, not `| null`; the store's
    // null means "no filter" and maps to absence at this boundary.
    this.track(this.api.findings({
      filters: this.filters(),
      code: this.code() ?? undefined,
      detector: this.detector() ?? undefined,
      sort: this.sort() ?? undefined,
      page: this.page(),
      size: this.size(),
    }), v => this.findings.set(v), { lane: 'findings' });
  }

  /** The axis facet is left out of the request: see `withoutFacet` for why, and the rail for the note. */
  loadCohorts(groupBy: FacetKey, baseline?: string): void {
    this.invalidateDetails();
    this.track(this.api.cohorts(groupBy, baseline, withoutFacet(this.filters(), groupBy)),
      v => this.cohorts.set(v), { lane: 'cohorts' });
  }

  /**
   * Same selection as the cohort table beside it, so the verdict and the deltas agree. The
   * previous verdict is dropped when the request goes out: it was computed over the previous
   * selection, and matching it to the table by axis, baseline and candidate alone let it stand
   * beside a new table of the same pair — and stay there if the new request failed.
   */
  loadJudge(groupBy: FacetKey, baseline: string, candidate: string): void {
    this.judge.set(null);
    this.track(this.api.judge(groupBy, baseline, candidate, withoutFacet(this.filters(), groupBy)),
      v => this.judge.set(v), { lane: 'judge' });
  }

  loadBreakdown(): void {
    this.track(this.api.breakdown(this.filters()), v => this.breakdown.set(v), { lane: 'breakdown' });
  }

  /** Both shared-filter endpoints; the rail reloads both when any facet changes. */
  loadAll(): void {
    this.loadOverview();
    this.loadFindings();
  }

  /**
   * Lazy, cached per id: two opens of the same id issue one request for the detail and one for
   * the calls around it. Both caches are cleared by any reload, so a re-indexed corpus cannot be
   * answered from stale evidence. The two load independently: a sequence that fails to arrive
   * must not take the evidence down with it.
   */
  selectFinding(id: number): void {
    // Each in its own lane, so only the newest open may land. Without it, opening one finding and
    // then another could let the first answer arrive last and overwrite the second's — the panel,
    // which only shows a context whose id matches the row, then waited on "Loading…" for a
    // sequence that had already arrived and been replaced. A cache hit takes its lane too, so an
    // older request still in flight cannot land over it.
    this.contextFailed.set(null);
    this.detailFailed.set(null);
    const cached = this.detailCache.get(id);
    if (cached !== undefined) {
      this.supersede('detail');
      this.detail.set(cached);
    } else {
      this.track(this.api.finding(id), v => {
        this.detailCache.set(id, v);
        this.detail.set(v);
      }, { lane: 'detail', onError: () => this.detailFailed.set(id) });
    }
    const context = this.contextCache.get(id);
    if (context !== undefined) {
      this.supersede('context');
      this.context.set(context);
    } else {
      this.track(this.api.context(id), v => {
        this.contextCache.set(id, v);
        this.context.set(v);
      }, { lane: 'context', onError: () => this.contextFailed.set(id) });
    }
  }

  /**
   * POST /api/index/run; on success reloads overview and findings. lastIndex carries the
   * returned counts for the status line ("indexed 168 streams, 389 findings in 4.3 s").
   */
  reindex(): void {
    this.indexing.set(true);
    this.track(this.api.runIndex(), v => {
      // Finding ids are never reused, so an open finding and the neighbours in its sequence
      // name rows the rebuilt index no longer has: a click on one would be a 404. The panel
      // closes on this (Findings watches `lastIndex`), and nothing of the old one is kept.
      this.detail.set(null);
      this.context.set(null);
      this.lastIndex.set(v);
      this.loadOverview();
      this.loadFindings();
      // A cohorts table that is on screen must not survive a re-index as a photograph of the
      // previous index. The axis signal doubles as "is anyone looking at it", so no request is
      // spent on a route nobody is on.
      if (this.cohortAxis() !== null) {
        this.loadCohorts(this.cohortGroupBy(), this.cohortBaseline() ?? undefined);
      }
    // Settled on both paths: a failed index must not leave the button claiming work that
    // stopped happening two seconds ago.
    }, { onSettled: () => this.indexing.set(false) });
  }

  // ---- internals ----

  private invalidateDetails(): void {
    this.detailCache.clear();
    this.contextCache.clear();
  }

  /** Take a lane's ticket without a request: whatever is still in flight on it will not land. */
  private supersede(lane: Lane): void {
    this.latest.set(lane, (this.latest.get(lane) ?? 0) + 1);
    this.settleLane(lane, null);
  }

  /**
   * Record a lane's outcome: a reason when its newest request failed, null when a new one goes
   * out. A new request is not failed yet, and the value it last had rejected is no answer to it.
   */
  private settleLane(lane: Lane, reason: string | null): void {
    const current = this.failures();
    if ((current.get(lane) ?? null) !== reason) {
      const next = new Map(current);
      if (reason === null) { next.delete(lane); } else { next.set(lane, reason); }
      this.failures.set(next);
    }
    if (reason === null && this.rejected()?.lane === lane) { this.rejected.set(null); }
  }

  private track<T>(source: Observable<T>, onValue: (value: T) => void,
                   { lane, onSettled, onError }: { lane?: Lane; onSettled?: () => void; onError?: () => void } = {}): void {
    this.busy.update(b => b + 1);
    const ticket = lane === undefined ? 0 : (this.latest.get(lane) ?? 0) + 1;
    if (lane !== undefined) {
      this.latest.set(lane, ticket);
      this.settleLane(lane, null);
    }
    // A superseded answer only settles the bookkeeping. Its data would overwrite a newer
    // view, and its error would put a bar over a screen whose current request succeeded.
    const superseded = (): boolean => lane !== undefined && this.latest.get(lane) !== ticket;
    // takeUntilDestroyed with the explicit DestroyRef: safe to call from any method, and
    // it completes every subscription when the store is destroyed, so a late response
    // cannot touch state after teardown.
    source.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => {
        this.busy.update(b => b - 1);
        onSettled?.();
        if (superseded()) { return; }
        this.error.set(null);
        this.notice.set(null);
        onValue(value);
      },
      error: err => {
        this.busy.update(b => b - 1);
        onSettled?.();
        if (superseded()) { return; }
        onError?.();
        if (lane !== undefined) {
          this.settleLane(lane, describeFailure(err));
          const body = (err as HttpErrorResponse | null)?.error;
          if (isProblem(body) && typeof body.filter === 'string') {
            this.rejected.set({ lane, filter: body.filter, value: body.value ?? null });
          }
        }
        // At most one bar, and it always describes the most recent answer. Two bars at once
        // ("a run is in progress" above "something failed") is a screen that argues with itself.
        //
        // A refused second index run is not a fault to report: the work the click asked for is
        // happening right now. In its own bar, in this file's own words — the server's `detail`
        // is written to be legible in a log, and it says "interleaved".
        if (isIndexAlreadyRunning((err as HttpErrorResponse | null)?.error)) {
          this.error.set(null);
          this.notice.set(ALREADY_INDEXING);
          return;
        }
        // A rejected request: surface the human sentence and leave the previous data in
        // place. An empty dashboard that means "you typed something wrong" reads as
        // "no problems found" — the most dangerous wrong answer this tool can give.
        this.notice.set(null);
        this.error.set(describeError(err));
      },
    });
  }
}

/** The screens whose loads replace one another: only the newest request of a lane may land. */
export type Lane = 'overview' | 'findings' | 'cohorts' | 'judge' | 'breakdown' | 'detail' | 'context';

/** The toolbar's own sentence for a refused second run. No promise the other run will refresh. */
const ALREADY_INDEXING =
  'An index run is already in progress, so this request was refused. Reload after it finishes to see the rebuilt index.';

/**
 * A failure as the screen that waited for it states it: the status, then the sentence. The
 * status is what tells a refused value (400) from a missing row (404) from a broken server
 * (5xx); 0 is a request that never got an answer at all.
 */
function describeFailure(err: unknown): string {
  const status = err instanceof HttpErrorResponse ? err.status : null;
  const sentence = describeError(err);
  if (status === null) { return sentence; }
  return status === 0 ? `no answer from the server · ${sentence}` : `${status} · ${sentence}`;
}

/** The error a human can act on: the problem+json sentence, or the transport message. */
function describeError(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error;
    if (body !== null && typeof body === 'object' && isProblem(body as ProblemDetail)) {
      return describeProblem(body as ProblemDetail);
    }
    if (typeof body === 'string' && body.length > 0) {
      return body;
    }
    return err.message;
  }
  return err instanceof Error ? err.message : 'Request failed';
}
