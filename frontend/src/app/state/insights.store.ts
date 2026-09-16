import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, computed, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { Observable } from 'rxjs';
import { describeProblem, isIndexAlreadyRunning, isProblem, type ProblemDetail } from '../api/problem';
import { ApiService } from '../api/api.service';
import type {
  CohortPageDto, FindingDetailDto, FindingsPageDto, IndexSummaryDto, OverviewDto,
  SortDir, SortField, Vocabulary,
} from '../api/types';
import { applyPreset, emptyFilters, type Filters, type PresetId } from './filters';

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
  readonly cohorts = signal<CohortPageDto | null>(null);
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

  /** The human sentence from the last rejected request; a later success clears it. */
  readonly error = signal<string | null>(null);

  /**
   * A request the server refused for a reason that is not a fault: an index run is already
   * going. It gets a bar of its own, in a colour that is not the error colour, because the one
   * thing this screen cannot afford is a red alert that means "nothing happened" — people start
   * dismissing the ones that do.
   */
  readonly notice = signal<string | null>(null);

  // Findings-only request state. The from/to/schema/model/preset/harnessVersion filters
  // above are shared with overview; these are not. The backend also accepts per-row
  // plane/detector/session/code filters (FindingsRequest); no screen drives them, so the
  // store holds no state for them — a filter nothing writes is not a filter, it is residue.
  readonly sort = signal<{ field: SortField; dir: SortDir } | null>(null);
  readonly page = signal(0);
  readonly size = signal(20);

  // ---- derived ----

  readonly preset = computed(() => this.filters().presetId);

  /**
   * Per-id cache for the lazy detail fetch, a plain Map for the session. Any reload
   * clears it: after the data is refetched, a cached detail is stale.
   */
  private detailCache = new Map<number, FindingDetailDto>();

  // ---- filter writes ----

  setFilters(patch: Partial<Filters>): void {
    this.filters.update(f => ({ ...f, ...patch }));
  }

  setPreset(id: PresetId, now: number = Date.now()): void {
    this.filters.update(f => applyPreset(f, id, now));
  }

  /** The rail's "Clear": nulls are written, not empty strings, so nothing is sent. */
  clearFilters(): void { this.filters.set(emptyFilters()); }

  /**
   * Dismiss the error bar. The bar shows whatever `error()` holds and the store is
   * the only writer: a later rejected request re-sets the signal and brings the bar
   * back, so dismissing can never hide a new failure permanently.
   */
  dismissError(): void { this.error.set(null); }

  /** Same rule as {@link dismissError}: the store is the only writer, so nothing stays hidden. */
  dismissNotice(): void { this.notice.set(null); }

  setSort(sort: { field: SortField; dir: SortDir } | null): void { this.sort.set(sort); }
  setPage(page: number): void { this.page.set(page); }

  // ---- loads: all of them through the one filter state ----

  loadOverview(): void {
    this.invalidateDetails();
    this.track(this.api.overview(this.filters()), v => {
      this.overview.set(v);
      this.vocabulary.set(v.vocabulary);
    });
  }

  loadFindings(): void {
    this.invalidateDetails();
    // FindingsRequest's optional fields are `| undefined`, not `| null`; the store's
    // null means "no filter" and maps to absence at this boundary.
    this.track(this.api.findings({
      filters: this.filters(),
      sort: this.sort() ?? undefined,
      page: this.page(),
      size: this.size(),
    }), v => this.findings.set(v));
  }

  loadCohorts(groupBy: string, baseline?: string): void {
    this.invalidateDetails();
    this.track(this.api.cohorts(groupBy, baseline, this.filters()), v => this.cohorts.set(v));
  }

  /** Both shared-filter endpoints; the rail reloads both when any facet changes. */
  loadAll(): void {
    this.loadOverview();
    this.loadFindings();
  }

  /**
   * Lazy, cached per id: two opens of the same id issue one request. The cache is cleared
   * by any reload, so a re-indexed corpus cannot be answered from stale evidence.
   */
  selectFinding(id: number): void {
    const cached = this.detailCache.get(id);
    if (cached !== undefined) {
      this.detail.set(cached);
      return;
    }
    this.track(this.api.finding(id), v => {
      this.detailCache.set(id, v);
      this.detail.set(v);
    });
  }

  /**
   * POST /api/index/run; on success reloads overview and findings. lastIndex carries the
   * returned counts for the status line ("indexed 168 streams, 389 findings in 4.3 s").
   */
  reindex(): void {
    this.indexing.set(true);
    this.track(this.api.runIndex(), v => {
      this.lastIndex.set(v);
      this.loadOverview();
      this.loadFindings();
      // A cohorts table that is on screen must not survive a re-index as a photograph of the
      // previous index. The axis signal doubles as "is anyone looking at it", so no request is
      // spent on a route nobody is on.
      const axis = this.cohortAxis();
      if (axis !== null) {
        this.loadCohorts(axis);
      }
    // Settled on both paths: a failed index must not leave the button claiming work that
    // stopped happening two seconds ago.
    }, () => this.indexing.set(false));
  }

  // ---- internals ----

  private invalidateDetails(): void {
    this.detailCache.clear();
  }

  private track<T>(source: Observable<T>, onValue: (value: T) => void,
                   onSettled?: () => void): void {
    this.busy.update(b => b + 1);
    // takeUntilDestroyed with the explicit DestroyRef: safe to call from any method, and
    // it completes every subscription when the store is destroyed, so a late response
    // cannot touch state after teardown.
    source.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => {
        this.busy.update(b => b - 1);
        onSettled?.();
        this.error.set(null);
        this.notice.set(null);
        onValue(value);
      },
      error: err => {
        this.busy.update(b => b - 1);
        onSettled?.();
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

/** The toolbar's own sentence for a refused second run. No promise the other run will refresh. */
const ALREADY_INDEXING =
  'An index run is already in progress, so this request was refused. Reload after it finishes to see the rebuilt index.';

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
