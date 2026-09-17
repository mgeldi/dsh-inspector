import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { OverviewDto } from '../api/types';
import type { PresetId } from '../state/filters';
import { InsightsStore } from '../state/insights.store';
import { PLANE_COLOURS } from '../charts/theme';
import { Overview } from './overview';

// jsdom has no ResizeObserver; the chart wrapper only needs the API surface.
class FakeResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
if (typeof globalThis.ResizeObserver === 'undefined') {
  (globalThis as { ResizeObserver?: unknown }).ResizeObserver = FakeResizeObserver;
}

// Mock the charting library at its four entry points, the way chart.spec.ts does: jsdom
// cannot host a canvas, and this spec is about the option the screen hands to the chart,
// not about ECharts internals. The captured option is what the gap test asserts on.
interface FakeChart {
  option: unknown | null;
  setOption(option: unknown, _replace?: boolean): void;
  resize(): void;
  dispose(): void;
}
const echartsMocks = vi.hoisted(() => {
  const charts: FakeChart[] = [];
  const init = (): FakeChart => {
    const chart: FakeChart = {
      option: null,
      setOption(option: unknown): void { chart.option = option; },
      resize(): void {},
      dispose(): void {},
    };
    charts.push(chart);
    return chart;
  };
  return { charts, use: (): void => {}, init };
});
vi.mock('echarts/core', () => echartsMocks);
vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {} }));
vi.mock('echarts/components', () => ({ GridComponent: {}, LegendComponent: {}, TooltipComponent: {} }));
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }));

// A stubbed store, per the plan: the screen reads one overview() signal and writes through
// setPreset / loadAll / reindex, so the stub only has to hold those four.
class StubStore {
  readonly overview = signal<OverviewDto | null>(null);
  readonly busy = signal(0);
  readonly setPresetCalls: PresetId[] = [];
  readonly showCodeCalls: (string | null)[] = [];
  loadAllCalls = 0;
  loadFindingsCalls = 0;
  reindexCalls = 0;
  setPreset(id: PresetId): void { this.setPresetCalls.push(id); }
  loadAll(): void { this.loadAllCalls += 1; }
  loadFindings(): void { this.loadFindingsCalls += 1; }
  showCode(code: string | null): void { this.showCodeCalls.push(code); }
  reindex(): void { this.reindexCalls += 1; }
}

// Invented fixture data — the measured counts from the plan's verified facts, not any
// real corpus. Paths, model names and ids elsewhere in this file follow the same rule.
const baseOverview: OverviewDto = {
  tiles: { sessions: 165, findings: 389, toolCalls: 16450, steps: 13733 },
  planeMix: { GUARD: 95, MODEL_MISUSE: 154, INFRASTRUCTURE: 140 },
  topDetectors: [{ detector: 'error-plane', count: 233 }, { detector: 'retry-storm', count: 72 }],
  topCodes: [{ code: 'FS_NOT_OBSERVED', count: 180 }, { code: 'FS_EDIT_NOT_FOUND', count: 125 }],
  series: [
    { day: '2026-09-01', findings: 46, toolCalls: 1043 },
    { day: '2026-09-02', findings: 12, toolCalls: 300 },
  ],
  throughput: [
    { schema: 'V0', timingSource: 'chunk-events', steps: 9040, medianDecodeTps: 163.4, medianTtftMs: 563.5 },
  ],
  vocabulary: { schemas: [], models: [], presets: [], harnessVersions: [], codes: [], detectors: [] },
};

describe('Overview', () => {
  let fixture: ComponentFixture<Overview>;
  let el: HTMLElement;
  let stub: StubStore;

  beforeEach(async () => {
    stub = new StubStore();
    await TestBed.configureTestingModule({
      imports: [Overview],
      providers: [{ provide: InsightsStore, useValue: stub }],
    }).compileComponents();

    fixture = TestBed.createComponent(Overview);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  function render(patch: Partial<OverviewDto> = {}): void {
    stub.overview.set({ ...baseOverview, ...patch });
    fixture.detectChanges();
  }

  it('renders the four tiles with thousands separators on the big counts', () => {
    render();
    expect(el.textContent).toContain('16,450');
    expect(el.textContent).toContain('13,733');
    expect(el.textContent).toContain('sessions in the index');
    expect(el.textContent).toContain('findings across all planes');
  });

  it('keeps the fixed plane order and renders a plane absent from planeMix as a zero row', () => {
    render({ planeMix: { GUARD: 95, INFRASTRUCTURE: 140 } }); // MODEL_MISUSE absent

    const rows = Array.from(el.querySelectorAll('.mix-row'));
    expect(rows.map(r => r.getAttribute('data-plane'))).toEqual(['GUARD', 'MODEL_MISUSE', 'INFRASTRUCTURE']);

    const missing = rows.find(r => r.getAttribute('data-plane') === 'MODEL_MISUSE')!;
    expect(missing, 'the absent plane is still a row').toBeTruthy();
    expect(missing.querySelector('.mix-count')!.textContent?.trim()).toBe('0');

    // the segment colours come from the shared chart tokens, never from a component hex.
    // Compare through a probe element because the CSSOM canonicalises the token's
    // hex notation to an rgb() string on read-back.
    const probe = document.createElement('span');
    probe.style.background = PLANE_COLOURS.GUARD;
    const guardSeg = Array.from(el.querySelectorAll('.mix-seg')) as HTMLElement[];
    expect(guardSeg.at(0)?.style.background).toBe(probe.style.background);
  });

  it('renders a null throughput median as n/a, and never as 0', () => {
    render({
      throughput: [
        { schema: 'V0', timingSource: 'none', steps: 1, medianDecodeTps: null, medianTtftMs: null },
        { schema: 'V0', timingSource: 'chunk-events', steps: 9040, medianDecodeTps: 163.4, medianTtftMs: 563.5 },
      ],
    });

    expect(el.textContent).toContain('n/a');
    expect(el.textContent).not.toMatch(/\b0(\.0+)?\s*(tok|ms)/);

    // the n/a cell names the reason in its tooltip
    const na = el.querySelector('td.na')!;
    expect(na).toBeTruthy();
    expect(na.getAttribute('title')).toContain("timing source 'none'");
    expect(na.getAttribute('title')).toContain('never 0');
  });

  it('labels throughput rows by convention AND timing source, per §3.3', () => {
    render({
      throughput: [
        { schema: 'V0', timingSource: 'chunk-events', steps: 9040, medianDecodeTps: 163.4, medianTtftMs: 563.5 },
        { schema: 'V3', timingSource: 'embedded-stream', steps: 472, medianDecodeTps: 121.8, medianTtftMs: 402.3 },
      ],
    });
    expect(el.textContent).toContain('chunk-events');
    expect(el.textContent).toContain('embedded-stream');
    expect(el.textContent).toContain('V0');
    expect(el.textContent).toContain('V3');
  });

  it('builds the daily axis over the full day range, so a gap stays a visible hole', async () => {
    render({
      series: [
        { day: '2026-09-01', findings: 3, toolCalls: 10 },
        { day: '2026-09-14', findings: 2, toolCalls: 4 },
      ],
    });
    await fixture.whenStable();

    const option = echartsMocks.charts.at(-1)!.option as {
      xAxis: { data: string[] };
      series: Array<{ data: Array<number | null> }>;
    };
    // 09-01 through 09-14 inclusive is 14 days: the twelve missing days are on the axis.
    expect(option.xAxis.data).toHaveLength(14);
    expect(option.xAxis.data[0]).toBe('09-01');
    expect(option.xAxis.data[13]).toBe('09-14');

    const data = option.series[0].data;
    expect(data[0]).toBe(3);
    expect(data[13]).toBe(2);
    expect(data.slice(1, 13).every(v => v === null), 'missing days are null, not drawn bars').toBe(true);
  });

  /**
   * The board had two breakdowns and neither answered "how often does this kind of error
   * happen": plane is three buckets, detector names this tool's own rules. The code is the
   * fact about the harness, and every count on the panel is a control — the rows behind it
   * were already browsable one screen away, with nothing connecting the two.
   */
  it('opens a code as the rows behind it, without touching the rail', () => {
    render();

    const entries = el.querySelectorAll('.codes .code-open');
    expect(entries.length).toBe(2);
    expect(entries[0].textContent).toContain('FS_NOT_OBSERVED');
    expect(entries[0].textContent).toContain('180');

    (entries[0] as HTMLElement).click();
    fixture.detectChanges();

    // the code is handed to the store and the rows are fetched before the route changes
    expect(stub.showCodeCalls).toEqual(['FS_NOT_OBSERVED']);
    expect(stub.loadFindingsCalls).toBe(1);
    // the shared rail is untouched: the code narrows that selection, it does not replace it
    expect(stub.setPresetCalls).toEqual([]);
  });

  /**
   * The panel is a top slice, so on any real index its column does not add up to the findings
   * tile beside it. Saying nothing invites the reader to sum it and conclude the tile is wrong.
   */
  it('states the findings its top slice leaves out, and says nothing when there are none', () => {
    // 389 findings, 305 of them in the two codes shown
    render();
    expect(el.querySelector('.code-tail')?.textContent).toContain('84');

    // a breakdown that is complete makes no claim about a tail
    render({ topCodes: [{ code: 'FS_NOT_OBSERVED', count: 389 }] });
    expect(el.querySelector('.code-tail')).toBeNull();
  });

  it('names the likely cause and offers the fix when the range has no findings', () => {
    render({
      tiles: { sessions: 0, findings: 0, toolCalls: 0, steps: 0 },
      planeMix: {}, topDetectors: [], topCodes: [], series: [], throughput: [],
    });

    const empty = el.querySelector('.empty');
    expect(empty, 'empty state visible').toBeTruthy();
    expect(empty!.textContent).toMatch(/All time/i);
    expect(empty!.textContent).toMatch(/indexer/i);
    expect(el.querySelector('.mixbar'), 'the zero charts that mislead are hidden').toBeNull();

    const allTime = Array.from(empty!.querySelectorAll('button'))
      .find(b => b.textContent?.includes('All time')) as HTMLButtonElement;
    allTime.click();
    expect(stub.setPresetCalls).toContain('all');
    expect(stub.loadAllCalls).toBe(1);

    const reindex = Array.from(empty!.querySelectorAll('button'))
      .find(b => b.textContent?.includes('Run indexer')) as HTMLButtonElement;
    reindex.click();
    expect(stub.reindexCalls).toBe(1);
  });
});
