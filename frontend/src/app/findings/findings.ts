import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { PLANE_COLOURS } from '../charts/theme';
import type { Category, FindingDetailDto, FindingDto, Plane, SortDir, SortField } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { FindingDetail, confidenceLabel, confidenceTip, planeLabel, timeShort } from './finding-detail';
import { ViewUrl } from '../state/view-url';
import { applyPreset } from '../state/filters';

/**
 * The findings table. Dense, sorted on the server, honest about what a page is:
 * a sorted page of 20 is a different claim than a sorted corpus of 389, so header
 * clicks write `sort` into the store and re-request — never sort a page client-side.
 */
@Component({
  selector: 'app-findings',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FindingDetail],
  templateUrl: './findings.html',
  styleUrls: ['./findings.scss', './findings-rows.scss'],
})
export class Findings {
  readonly store = inject(InsightsStore);
  private readonly url = inject(ViewUrl);
  readonly PLANE_COLOURS = PLANE_COLOURS;

  readonly loaded = computed(() => this.store.findings() !== null);
  readonly items = computed(() => this.store.findings()?.items ?? []);
  readonly total = computed(() => this.store.findings()?.total ?? 0);

  /**
   * The sort the server is answering. The store's null means "the backend default"
   * (time:desc), and that is what the header indicators show — the arrow is never
   * painted on a sort the request is not actually carrying.
   */
  readonly activeSort = computed(() => this.store.sort() ?? { field: 'time' as SortField, dir: 'desc' as SortDir });

  /**
   * The honest pager: the slice this page shows of the corpus total. Computed from
   * page/size/total the server reported, never from the length of the current array.
   */
  readonly slice = computed(() => {
    const p = this.store.findings();
    if (!p || p.total === 0) { return null; }
    return {
      first: p.page * p.size + 1,
      last: Math.min((p.page + 1) * p.size, p.total),
      total: p.total,
    };
  });

  readonly canPrev = computed(() => (this.store.findings()?.page ?? 0) > 0);
  readonly canNext = computed(() => {
    const p = this.store.findings();
    return p !== null && (p.page + 1) * p.size < p.total;
  });

  // ---- detail panel: opened by row click, fed by the store's cached detail ----

  private readonly openId = signal<number | null>(null);
  readonly openDetail = computed<FindingDetailDto | null>(() => {
    const id = this.openId();
    const d = this.store.detail();
    // A detail in flight for another id is not this row's: the panel stays closed
    // rather than showing the previous finding under the new row's click.
    return id !== null && d !== null && d.finding.id === id ? d : null;
  });
  readonly detailLoading = computed(() => this.openId() !== null && this.openDetail() === null);

  open(f: FindingDto): void {
    this.openId.set(f.id);
    this.store.selectFinding(f.id);
  }

  /** The row's primary control (the focusable detector cell) calls this, not `open` again. */
  openRow(ev: Event, f: FindingDto): void {
    ev.stopPropagation();
    this.open(f);
  }

  close(): void { this.openId.set(null); }

  // ---- server-side sort ----

  toggleSort(field: SortField): void {
    const cur = this.activeSort();
    // Clicking the active header flips its direction; a new header starts desc —
    // the way the backend's default reads, and the direction users scan first.
    const dir: SortDir = cur.field === field ? (cur.dir === 'desc' ? 'asc' : 'desc') : 'desc';
    this.url.patch({ sort: { field, dir }, page: 0 });
  }

  isSorted(field: SortField): boolean {
    return this.activeSort().field === field;
  }

  /** The direction on the active column, null on every other column. */
  dirFor(field: SortField): SortDir | null {
    const cur = this.activeSort();
    return cur.field === field ? cur.dir : null;
  }

  /** The WAI-ARIA sort state for the header: the caret paints it, the attribute states it. */
  ariaSortFor(field: SortField): 'ascending' | 'descending' | 'none' {
    const dir = this.dirFor(field);
    if (dir === 'asc') { return 'ascending'; }
    if (dir === 'desc') { return 'descending'; }
    return 'none';
  }

  /** Headers are role="button": Enter and Space trigger the same sort a click does. */
  onHeaderKeydown(ev: KeyboardEvent, field: SortField): void {
    if (ev.key === 'Enter' || ev.key === ' ') {
      ev.preventDefault();
      this.toggleSort(field);
    }
  }

  // ---- pagination ----

  /** How many pages the current total divides into; at least one, so "1 of 1" is never "1 of 0". */
  readonly pageCount = computed(() => {
    const p = this.store.findings();
    if (!p || p.total === 0) { return 1; }
    return Math.ceil(p.total / p.size);
  });

  readonly currentPage = computed(() => (this.store.findings()?.page ?? 0) + 1);

  /**
   * The numbered window: first, last, the current page and its neighbours, with a gap
   * marker where numbers were skipped. 455 findings at 20 a page is 23 pages, and Prev/Next
   * alone made page 23 twenty-two clicks away — a pager that can only walk is not a pager on
   * a table this long. The window never changes width, so the buttons do not move under the
   * pointer as you page through.
   */
  readonly pageWindow = computed<(number | 'gap')[]>(() => {
    const count = this.pageCount();
    const current = this.currentPage();
    if (count <= 7) {
      return Array.from({ length: count }, (_, i) => i + 1);
    }
    const out: (number | 'gap')[] = [1];
    let from = Math.max(2, current - 1);
    let to = Math.min(count - 1, current + 1);
    // Keep the width constant at the ends, where the window would otherwise be lopsided.
    if (current <= 3) { from = 2; to = 4; }
    if (current >= count - 2) { from = count - 3; to = count - 1; }
    if (from > 2) { out.push('gap'); }
    for (let i = from; i <= to; i++) { out.push(i); }
    if (to < count - 1) { out.push('gap'); }
    out.push(count);
    return out;
  });

  goPage(delta: number): void {
    this.toPage(this.currentPage() + delta);
  }

  /** One-based, because that is what the control shows. Out-of-range jumps are ignored. */
  toPage(oneBased: number): void {
    const target = oneBased - 1;
    if (target < 0 || target >= this.pageCount() || target === this.store.page()) { return; }
    this.url.patch({ page: target });
  }

  /**
   * Rows per page. The page index is reset rather than rescaled: page 7 of 23 at twenty rows
   * is not page 7 of 5 at a hundred, and landing somewhere in the middle of a different
   * slicing of the same data is worse than landing at the start of it.
   */
  setSize(size: number): void {
    if (size === this.store.size()) { return; }
    this.url.patch({ size, page: 0 });
  }

  readonly sizes = [20, 50, 100] as const;

  /** The empty-state fix: the range is the usual suspect, so widen it and reload both screens. */
  useAllTime(): void {
    this.url.patch({ filters: applyPreset(this.store.filters(), 'all'), page: 0 });
  }

  // ---- cell renderers, the rules stated once and used everywhere ----

  /**
   * The code the overview handed over, if any. It has to be on screen: arriving at a table
   * showing 89 of 455 rows with nothing saying why is the same failure as an empty dashboard
   * that means "you typed something wrong" — the number looks like the answer to the question
   * the rail appears to be asking, and it is the answer to a different one.
   */
  readonly activeCode = computed(() => this.store.code());

  clearCode(): void {
    this.url.patch({ code: null, page: 0 });
  }

  confidenceWord(c: number | null, category: Category | null): string { return confidenceLabel(c, category); }
  confidenceTipFor(c: number | null, category: Category | null): string { return confidenceTip(c, category); }
  planeName(p: Plane): string { return planeLabel(p); }
  fmtTimeShort = timeShort;
}
