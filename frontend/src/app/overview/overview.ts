import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, untracked } from '@angular/core';
import type { EChartsCoreOption } from 'echarts/core';
import { ChartComponent } from '../charts/chart';
import { CHART_AXIS, CHART_BASE, DAILY_BAR_COLOUR, PLANE_COLOURS } from '../charts/theme';
import { InsightsStore } from '../state/insights.store';
import type { BreakdownRow, Plane } from '../api/types';
import { categoryChip } from '../findings/finding-words';
import { ViewUrl } from '../state/view-url';
import { applyPreset } from '../state/filters';

const DAY_MS = 86_400_000;

// The plane order the mix is always rendered in: Guard, Model misuse, Infrastructure —
// never by count. A chart that reorders between visits makes the eye relearn it, and the
// reordering between two visits of the same data would read as data movement.
const PLANE_ORDER: readonly Plane[] = ['GUARD', 'MODEL_MISUSE', 'INFRASTRUCTURE'];

const PLANE_LABELS: Record<Plane, string> = {
  GUARD: 'Guard',
  MODEL_MISUSE: 'Model misuse',
  INFRASTRUCTURE: 'Infrastructure',
};

interface TileRow { key: string; value: number; context: string; }
interface PlaneRow { plane: Plane; count: number; share: number; shareText: string; }
/** `code`: the qualifier is a harness code, set as an identifier; else it is a category in words. */
interface KindRow { row: BreakdownRow; qualifier: string | null; code: boolean; }

/**
 * How many kinds the panel lists. Ten is where the measured breakdown turns from kinds that
 * recur to kinds seen once or twice; the rest is stated as a count below the list.
 */
const TOP_KINDS = 10;

/**
 * The dashboard over one filter state. Everything on this screen is read from the store;
 * the component builds chart options and labels, and never builds a query — the store owns
 * every fetch, so the tile board and the findings table can never describe different data.
 */
@Component({
  selector: 'app-overview',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ChartComponent, DecimalPipe],
  templateUrl: './overview.html',
  styleUrl: './overview.scss',
})
export class Overview {
  readonly store = inject(InsightsStore);
  private readonly url = inject(ViewUrl);
  readonly PLANE_COLOURS = PLANE_COLOURS;

  readonly overview = computed(() => this.store.overview());
  readonly busy = computed(() => this.store.busy() > 0);

  /**
   * The four tiles. Every count is a measurement, so the value carries tabular numerals and
   * the context line says what was counted — a bare 16,450 is a number, not a claim.
   */
  readonly tiles = computed<TileRow[] | null>(() => {
    const t = this.store.overview()?.tiles;
    if (!t) { return null; }
    return [
      { key: 'sessions', value: t.sessions, context: 'sessions in the index' },
      { key: 'findings', value: t.findings, context: 'findings across all planes' },
      { key: 'toolCalls', value: t.toolCalls, context: 'observed tool calls' },
      { key: 'steps', value: t.steps, context: 'completed steps' },
    ];
  });

  /**
   * The plane mix in the fixed order above, with a row per plane. A plane absent from
   * planeMix is zero — and still rendered: absence of findings is a measurement, and
   * dropping the row would make the zero indistinguishable from a UI bug.
   */
  readonly planeRows = computed<PlaneRow[]>(() => {
    const mix = this.store.overview()?.planeMix ?? {};
    const total = PLANE_ORDER.reduce((sum, p) => sum + (mix[p] ?? 0), 0);
    return PLANE_ORDER.map(plane => {
      const count = mix[plane] ?? 0;
      return {
        plane,
        count,
        share: total === 0 ? 0 : count / total,
        shareText: total === 0 ? '0.0%' : `${((count / total) * 100).toFixed(1)}%`,
      };
    });
  });

  /**
   * The daily findings series, as a bar option. The x-axis is the full day range between
   * the first and the last bucket, and days with no bucket are null — a visible hole on the
   * axis. A line or bar drawn straight across a two-week gap claims continuous activity;
   * null (rather than 0) keeps the tooltip honest: no bar, no invented zero count.
   */
  readonly seriesOption = computed<EChartsCoreOption | null>(() => {
    const series = this.store.overview()?.series ?? [];
    if (series.length === 0) { return null; }
    const byDay = new Map<string, number>(series.map(s => [s.day, s.findings]));
    const days = dayRange(series.map(s => s.day));
    const data: Array<number | null> = days.map(d => byDay.get(d) ?? null);
    // CHART_BASE carries the app's dark tooltip and the short settle animation. The option
    // below only adds what is specific to this series; without the base, ECharts merges
    // this option with its *default theme* and the tooltip renders as the default white box.
    return {
      ...CHART_BASE,
      backgroundColor: 'transparent',
      textStyle: { color: CHART_AXIS.textLo },
      grid: {
        left: 8, right: 16, top: 24, bottom: 8,
        // ECharts 6 deprecates containLabel; its own docs give the exact equivalent as
        // {outerBoundsMode: 'same', outerBoundsContain: 'axisLabel'}. Left as containLabel it
        // logged a deprecation notice on every chart render.
        outerBoundsMode: 'same', outerBoundsContain: 'axisLabel',
      },
      xAxis: {
        type: 'category',
        data: days.map(d => d.slice(5)), // 'MM-dd' labels: the year is constant inside one range
        axisLine: { lineStyle: { color: CHART_AXIS.hairline } },
        axisTick: { show: false },
        axisLabel: { color: CHART_AXIS.textLo },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        axisLine: { show: false },
        splitLine: { lineStyle: { color: CHART_AXIS.hairline } },
        axisLabel: { color: CHART_AXIS.textLo },
      },
      series: [{
        type: 'bar',
        data,
        barMaxWidth: 24,
        itemStyle: { color: DAILY_BAR_COLOUR },
      }],
    };
  });

  readonly detectors = computed(() => this.store.overview()?.topDetectors ?? []);

  /**
   * The breakdown by error code. `topDetectors` answers "which of my rules fired", which is a
   * fact about this tool; this answers "what did the harness refuse", which is the fact a
   * reader can act on — and until it existed the overview could not answer "how often does
   * this kind of error happen" at all, though every row behind it was already browsable.
   */
  readonly codes = computed(() => this.store.overview()?.topCodes ?? []);

  /**
   * What the panel is not showing. It lists the top few codes, so on any real index the
   * column does not add up to the findings tile beside it — 358 of 389 on the measured
   * corpus, in 8 of 20 codes. A breakdown that silently omits its tail invites the reader to
   * sum it and get a number that contradicts the tile, which is the same class of error as a
   * wrong denominator. Only the findings count is stated: the number of remaining codes would
   * have to come from the vocabulary, and that list is deliberately index-wide while these
   * counts follow the filter, so under an active rail it would be a different question's
   * answer.
   *
   * Findings with no code at all are not in the tail: they are not "in other codes", and the
   * panel states them on a line of their own (`uncoded` below), so rows + tail + uncoded is
   * the findings tile exactly.
   */
  readonly codeTail = computed<number>(() => {
    const o = this.store.overview();
    if (o === null) { return 0; }
    const shown = o.topCodes.reduce((sum, c) => sum + c.count, 0);
    return Math.max(0, o.tiles.findings - shown - this.uncoded());
  });

  /**
   * Findings that carry no error code — a shell rewrite of a tracked file is an event the
   * detector saw, not a refusal the harness issued. They used to be folded into the list as a
   * bar named `unknown`, which read as one more harness code and opened as a filter no code
   * matches. `?? 0` keeps the panel whole against a backend that predates the field.
   */
  readonly uncoded = computed<number>(() => this.store.overview()?.uncodedFindings ?? 0);
  readonly throughput = computed(() => this.store.overview()?.throughput ?? []);

  /**
   * Findings by kind: the detector and what it concluded, the finest grain there is. The codes
   * panel says what the harness refused; this says which of those refusals are one failure and
   * which are four — an edit miss after a read and a blind retry of the same miss share a code
   * and call for different fixes. Busiest first, as the backend sends it.
   */
  readonly kinds = computed<KindRow[]>(() =>
    (this.store.breakdown() ?? []).slice(0, TOP_KINDS)
      .map(row => ({ row, qualifier: kindQualifier(row), code: row.category === null })));

  /** The findings in kinds below the top slice, stated the way the codes panel states its tail. */
  readonly kindTail = computed<number>(() =>
    (this.store.breakdown() ?? []).slice(TOP_KINDS).reduce((sum, k) => sum + k.count, 0));

  /**
   * A count on the board, opened as the rows behind it. The shared rail filters stay exactly
   * as they are — the code narrows the same selection rather than replacing it — so the
   * number on the summary and the number of rows that arrive are the same number.
   */
  openCode(code: string): void {
    // One navigation carries both the screen and the narrowing. The shell reads the new URL
    // and issues the fetch, so the table cannot arrive showing the previous, unfiltered page
    // under a banner announcing a filter — and the link is shareable as what it shows. It
    // replaces a detector drill-down rather than stacking on it: each opens one count's rows.
    this.url.go(['/findings'], { code, detector: null, page: 0 });
  }

  /**
   * A kind, opened as its detector's rows. The backend filters by detector, not by category or
   * detail, so the table can hold more rows than the kind's count — the findings screen names
   * the detector it narrowed to, and the rows show the category that tells the kinds apart.
   */
  openKind(detector: string): void {
    this.url.go(['/findings'], { detector, code: null, page: 0 });
  }

  /** What a kind's row does when chosen, in words: all of its detector's findings, not only this kind's. */
  kindAction(k: KindRow): string {
    const kind = k.qualifier === null ? '' : ` (this row counts only ${k.qualifier})`;
    return `Open all ${k.row.detector} findings${kind}`;
  }

  /**
   * The screen-level empty state. tiles.findings === 0 means the current range holds no
   * findings — and silence on that screen would read as "nothing wrong found", the most
   * dangerous wrong answer this tool can give, so the state names the likely cause and
   * offers the fix instead of rendering nothing.
   */
  readonly isEmpty = computed(() => this.store.overview()?.tiles?.findings === 0);

  readonly mixAria = computed(() =>
    this.planeRows()
      .map(r => `${this.planeName(r.plane)} ${r.count} (${r.shareText})`)
      .join(', '));

  planeName(p: Plane): string { return PLANE_LABELS[p]; }

  /**
   * A null median renders n/a, never 0: a whole cohort can have timing source `none`, and
   * plotting its median as 0 invents a zero-throughput cohort — the exact mistake §3.3
   * documents. 0.9 is a measurement; n/a is an absence.
   */
  tps(v: number | null): string { return v === null ? 'n/a' : `${v.toFixed(1)} tok/s`; }
  ttft(v: number | null): string { return v === null ? 'n/a' : `${v.toFixed(1)} ms`; }

  /** The tooltip on an n/a cell: the timing source that makes the median unmeasurable. */
  medianTip(source: string): string {
    return `timing source '${source}' records no chunk timestamps, so the median is undefined — rendered n/a, never 0`;
  }

  useAllTime(): void {
    this.url.patch({ filters: applyPreset(this.store.filters(), 'all'), page: 0 });
  }

  runIndexer(): void { this.store.reindex(); }

  /** Ask again after a failed load: the overview for the rail's vocabulary, and this screen's kinds. */
  retry(): void {
    this.store.loadOverview();
    this.store.loadBreakdown();
  }

  constructor() {
    // This screen owns the breakdown request, as the cohorts screen owns its own: the shell
    // loads what the rail needs, and nobody on the findings tab pays for a panel they cannot
    // see. Reading the filters and the last index run is what re-asks after either changes.
    effect(() => {
      this.store.filters();
      this.store.lastIndex();
      untracked(() => this.store.loadBreakdown());
    });
  }
}

/**
 * What a kind is, after its detector: the category in words — and its detail, when the detector
 * splits one category by it (a shell edit is a direct mutation by script, by redirect, in place);
 * without it three rows read "shell-edit direct mutation" with three different counts — else the
 * code and its detail.
 */
function kindQualifier(k: BreakdownRow): string | null {
  if (k.category !== null) {
    const words = categoryChip(k.category).label;
    return k.detail === null ? words : `${words} · ${k.detail.toLowerCase().replace(/_/g, ' ')}`;
  }
  const parts = [k.code, k.detail].filter((p): p is string => p !== null);
  return parts.length === 0 ? null : parts.join(' / ');
}

/**
 * Every day between the first and the last bucket, inclusive, UTC midnight to UTC midnight.
 * The series is already day-bucketed by the backend, so day arithmetic on 'YYYY-MM-DD'
 * strings through Date.parse is exact.
 */
function dayRange(days: string[]): string[] {
  const stamps = days
    .map(d => Date.parse(`${d}T00:00:00Z`))
    .filter(n => !Number.isNaN(n));
  if (stamps.length === 0) { return []; }
  const start = Math.min(...stamps);
  const end = Math.max(...stamps);
  const out: string[] = [];
  for (let t = start; t <= end; t += DAY_MS) {
    out.push(new Date(t).toISOString().slice(0, 10));
  }
  return out;
}
