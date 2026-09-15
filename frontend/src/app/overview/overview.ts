import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import type { EChartsCoreOption } from 'echarts/core';
import { ChartComponent } from '../charts/chart';
import { CHART_AXIS, CHART_BASE, DAILY_BAR_COLOUR, PLANE_COLOURS } from '../charts/theme';
import { InsightsStore } from '../state/insights.store';
import type { Plane } from '../api/types';

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
      grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
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
  readonly throughput = computed(() => this.store.overview()?.throughput ?? []);

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
    this.store.setPreset('all');
    this.store.loadAll();
  }

  runIndexer(): void { this.store.reindex(); }
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
