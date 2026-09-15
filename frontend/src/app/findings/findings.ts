import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { PLANE_COLOURS } from '../charts/theme';
import type { FindingDetailDto, FindingDto, Plane, SortDir, SortField } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { FindingDetail, confidenceLabel, confidenceTip, planeLabel, timeShort } from './finding-detail';

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
  styleUrl: './findings.scss',
})
export class Findings {
  readonly store = inject(InsightsStore);
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

  close(): void { this.openId.set(null); }

  // ---- server-side sort ----

  toggleSort(field: SortField): void {
    const cur = this.activeSort();
    // Clicking the active header flips its direction; a new header starts desc —
    // the way the backend's default reads, and the direction users scan first.
    const dir: SortDir = cur.field === field ? (cur.dir === 'desc' ? 'asc' : 'desc') : 'desc';
    this.store.setSort({ field, dir });
    this.store.loadFindings();
  }

  arrow(field: SortField): string {
    const cur = this.activeSort();
    return cur.field === field ? (cur.dir === 'desc' ? '↓' : '↑') : '';
  }

  // ---- pagination ----

  goPage(delta: number): void {
    const p = this.store.findings();
    if (!p) { return; }
    const next = p.page + delta;
    if (next < 0 || (next + 1) * p.size > p.total) { return; }
    this.store.setPage(next);
    this.store.loadFindings();
  }

  /** The empty-state fix: the range is the usual suspect, so widen it and reload both screens. */
  useAllTime(): void {
    this.store.setPreset('all');
    this.store.loadAll();
  }

  // ---- cell renderers, the rules stated once and used everywhere ----

  confidenceWord(c: number | null): string { return confidenceLabel(c); }
  confidenceTipFor(c: number | null): string { return confidenceTip(c); }
  planeName(p: Plane): string { return planeLabel(p); }
  fmtTimeShort = timeShort;
}
