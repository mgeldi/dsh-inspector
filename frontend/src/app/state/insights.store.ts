import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, computed, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { Observable } from 'rxjs';
import { describeProblem, isProblem, type ProblemDetail } from '../api/problem';
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

  /** The human sentence from the last rejected request; a later success clears it. */
  readonly error = signal<string | null>(null);

  // Findings-only request state. The from/to/schema/model/preset/harnessVersion filters
  // above are shared with overview; these are not.
  readonly plane = signal<string | null>(null);
  readonly detector = signal<string | null>(null);
  readonly session = signal<string | null>(null);
  readonly code = signal<string | null>(null);
  readonly sort = signal<{ field: SortField; dir: SortDir } | null>(null);
  readonly page = signal(0);
  readonly size = signal(20);

  // ---- derived ----

  readonly preset = computed(() => this.filters().presetId);
  readonly totalCount = computed(() => this.findings()?.total ?? 0);

  /**
   * Per-id cache for the lazy detail fetch, a plain Map for the session. Any reload
   * clears it: after the data is refetched, a cached detail is stale.
   */
  private detailCache = new Map<number, FindingDetailDto>();

  // ---- filter writes ----

  setFilters(patch: Partial<Filters>): void {
    this.filters.update(f => ({ ...f, ...patch }));
  }

  setSchema(value: string | null): void { this.setFilters({ schema: value }); }
  setModel(value: string | null): void { this.setFilters({ model: value }); }
  setHarnessVersion(value: string | null): void { this.setFilters({ harnessVersion: value }); }

  setPreset(id: PresetId, now: number = Date.now()): void {
    this.filters.update(f => applyPreset(f, id, now));
  }

  /** The rail's "Clear": nulls are written, not empty strings, so nothing is sent. */
  clearFilters(): void { this.filters.set(emptyFilters()); }

  setPlane(value: string | null): void { this.plane.set(value); }
  setDetector(value: string | null): void { this.detector.set(value); }
  setSession(value: string | null): void { this.session.set(value); }
  setCode(value: string | null): void { this.code.set(value); }
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
      plane: this.plane() ?? undefined,
      detector: this.detector() ?? undefined,
      session: this.session() ?? undefined,
      code: this.code() ?? undefined,
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
    this.track(this.api.runIndex(), v => {
      this.lastIndex.set(v);
      this.loadOverview();
      this.loadFindings();
    });
  }

  // ---- internals ----

  private invalidateDetails(): void {
    this.detailCache.clear();
  }

  private track<T>(source: Observable<T>, onValue: (value: T) => void): void {
    this.busy.update(b => b + 1);
    // takeUntilDestroyed with the explicit DestroyRef: safe to call from any method, and
    // it completes every subscription when the store is destroyed, so a late response
    // cannot touch state after teardown.
    source.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => {
        this.busy.update(b => b - 1);
        this.error.set(null);
        onValue(value);
      },
      error: err => {
        this.busy.update(b => b - 1);
        // A rejected request: surface the human sentence and leave the previous data in
        // place. An empty dashboard that means "you typed something wrong" reads as
        // "no problems found" — the most dangerous wrong answer this tool can give.
        this.error.set(describeError(err));
      },
    });
  }
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
