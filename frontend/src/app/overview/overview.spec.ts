import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { computed, signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { BreakdownRow, IndexSummaryDto, OverviewDto } from '../api/types';
import { applyPreset, emptyFilters } from '../state/filters';
import { InsightsStore } from '../state/insights.store';
import { PLANE_COLOURS } from '../charts/theme';
import { Overview } from './overview';
import { ViewUrl } from '../state/view-url';

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

/** Records where a control wanted to go, which is the component's whole job now. */
class StubUrl {
  readonly gos: { path: readonly string[]; state: Record<string, unknown> }[] = [];
  readonly patches: Record<string, unknown>[] = [];
  patch(state: Record<string, unknown>): void { this.patches.push(state); }
  go(path: readonly string[], state: Record<string, unknown>): void { this.gos.push({ path, state }); }
}

/** The store as this screen reads it: the overview, the breakdown, the filters, and reindex. */
class StubStore {
  readonly overview = signal<OverviewDto | null>(null);
  readonly breakdown = signal<BreakdownRow[] | null>(null);
  readonly busy = signal(0);
  readonly filters = signal(emptyFilters());
  readonly lastIndex = signal<IndexSummaryDto | null>(null);
  readonly preset = computed(() => this.filters().presetId);
  /** The lane whose newest request failed, as the store's `failed(lane)` reports it. */
  readonly failedLane = signal<string | null>(null);
  failed(lane: string): boolean { return this.failedLane() === lane; }
  failure(lane: string): string | null { return this.failed(lane) ? '500 · Internal error' : null; }
  loadOverview(): void { /* the stub loads nothing */ }
  reindexCalls = 0;
  loadBreakdownCalls = 0;
  reindex(): void { this.reindexCalls += 1; }
  loadBreakdown(): void { this.loadBreakdownCalls += 1; }
}

// Invented fixture data — the measured counts from the plan's verified facts, not any
// real corpus. Paths, model names and ids elsewhere in this file follow the same rule.
const baseOverview: OverviewDto = {
  tiles: { sessions: 165, findings: 389, toolCalls: 16450, steps: 13733 },
  planeMix: { GUARD: 95, MODEL_MISUSE: 154, INFRASTRUCTURE: 140 },
  topDetectors: [{ detector: 'error-plane', count: 233 }, { detector: 'retry-storm', count: 72 }],
  topCodes: [{ code: 'FS_NOT_OBSERVED', count: 180 }, { code: 'FS_EDIT_NOT_FOUND', count: 125 }],
  uncodedFindings: 0,
  series: [
    { day: '2026-09-01', findings: 46, toolCalls: 1043 },
    { day: '2026-09-02', findings: 12, toolCalls: 300 },
  ],
  throughput: [
    { schema: 'V0', timingSource: 'chunk-events', steps: 9040, medianDecodeTps: 163.4, medianTtftMs: 563.5 },
  ],
  vocabulary: {
    schemas: [], models: [], providers: [], roles: [], presets: [], harnessVersions: [], codes: [], detectors: [],
  },
};

describe('Overview', () => {
  let fixture: ComponentFixture<Overview>;
  let el: HTMLElement;
  let stub: StubStore;
  let url: StubUrl;

  beforeEach(async () => {
    stub = new StubStore();
    url = new StubUrl();
    await TestBed.configureTestingModule({
      imports: [Overview],
      providers: [
        { provide: InsightsStore, useValue: stub },
        { provide: ViewUrl, useValue: url },
      ],
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

    // One navigation carries both the screen and the narrowing, and the shell turns that
    // into the fetch — so the link is shareable as exactly what it shows.
    expect(url.gos).toHaveLength(1);
    expect(url.gos[0].path).toEqual(['/findings']);
    // It replaces a detector drill-down rather than stacking on it: each opens one count's rows.
    expect(url.gos[0].state).toEqual({ code: 'FS_NOT_OBSERVED', detector: null, page: 0 });
    // the shared rail is untouched: the code narrows that selection, it does not replace it
    expect(url.patches).toEqual([]);
    expect(url.gos[0].state).not.toHaveProperty('filters');
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

  /**
   * A shell rewrite of a tracked file is an event, not a refusal: it has no code. Folded into
   * the tail it would claim to sit "in other codes"; as a bar it read as one more harness code.
   * It gets its own line, and the three parts of the panel add up to the tile.
   */
  it('states the findings that carry no code apart from the tail, and the panel sums to the tile', () => {
    // 389 findings: 305 in the two codes shown, 20 without any code, so 64 in other codes
    render({ uncodedFindings: 20 });

    const tail = el.querySelector('.code-tail .code-other')!.textContent!;
    const uncoded = el.querySelector('.code-tail .code-uncoded')!.textContent!;
    expect(tail).toContain('+64 findings in other codes');
    expect(uncoded).toContain('20 findings carry no error code');

    const listed = Array.from(el.querySelectorAll('.codes .det-count'))
      .reduce((sum, c) => sum + Number(c.textContent!.replace(/,/g, '')), 0);
    const number = (text: string) => Number(/[\d,]+/.exec(text)![0].replace(/,/g, ''));
    expect(listed + number(tail) + number(uncoded), 'rows + tail + uncoded is the tile').toBe(389);
    expect(el.querySelector('.codes')!.textContent, 'no pseudo-code bar').not.toMatch(/unknown|null/);

    // every finding coded: no uncoded line at all, rather than a zero
    render({ uncodedFindings: 0 });
    expect(el.querySelector('.code-uncoded')).toBeNull();

    // and a panel whose rows are complete but some findings carry no code says only that
    render({ topCodes: [{ code: 'FS_NOT_OBSERVED', count: 380 }], uncodedFindings: 9 });
    expect(el.querySelector('.code-other')).toBeNull();
    expect(el.querySelector('.code-uncoded')!.textContent).toContain('9 findings carry no error code');
  });

  it('names the likely cause and offers the fix when the range has no findings', () => {
    stub.filters.set(applyPreset(emptyFilters(), '7d', 1_790_000_000_000));
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
    // The fix navigates rather than reaching into the store, so the widened range is in the
    // URL and the screen it produces can be linked to like any other.
    expect(url.patches).toHaveLength(1);
    expect((url.patches[0]['filters'] as { presetId: string }).presetId).toBe('all');
    expect(url.patches[0]['page']).toBe(0);

    const reindex = Array.from(empty!.querySelectorAll('button'))
      .find(b => b.textContent?.includes('Run indexer')) as HTMLButtonElement;
    reindex.click();
    expect(stub.reindexCalls).toBe(1);
  });

  /**
   * The codes panel says what the harness refused; the kinds panel says which of those refusals
   * are one failure and which are several — a blind retry and a miss after a read share a code.
   */
  describe('findings by kind', () => {
    const kind = (detector: string, count: number, over: Partial<BreakdownRow> = {}): BreakdownRow => ({
      detector, plane: 'MODEL_MISUSE', category: null, code: null, detail: null,
      count, perKCalls: count / 10, ...over,
    });

    it('asks for the breakdown itself, and again when the filters or the index change', () => {
      TestBed.tick();
      expect(stub.loadBreakdownCalls, 'on first render').toBe(1);

      stub.filters.set({ ...emptyFilters(), schema: 'V3' });
      TestBed.tick();
      expect(stub.loadBreakdownCalls, 'a filter change re-asks').toBe(2);

      stub.lastIndex.set({
        streams: 2, sessions: 2, steps: 3, toolCalls: 4, findings: 5,
        evidenceRows: 0, pruned: 0, parseFailures: 0, durationMs: 10,
      });
      TestBed.tick();
      expect(stub.loadBreakdownCalls, 'a re-index re-asks').toBe(3);
    });

    /**
     * Before the breakdown has answered, the panel must not say "no findings": an empty claim
     * shown while a request is still out reads as "nothing is wrong", which is the wrong answer
     * this tool exists not to give. Measured on the real screen, it showed for the first moments
     * of every load, beside a tile that already said seventeen.
     */
    it('says it is loading until the breakdown answers, and only then whether it is empty', () => {
      render();
      stub.breakdown.set(null);
      fixture.detectChanges();
      const panel = () => (Array.from(el.querySelectorAll('h3'))
        .find(h => /kind/i.test(h.textContent ?? ''))?.parentElement?.textContent ?? '');
      expect(panel()).toContain('Loading');
      expect(panel()).not.toContain('No findings');

      stub.breakdown.set([]);
      fixture.detectChanges();
      expect(panel()).toContain('No findings in this selection');
    });

    it('lists the busiest ten with what each detector concluded, and states the rest', () => {
      const rows: BreakdownRow[] = [
        kind('edit-miss', 60, { category: 'REPEATED_MISS', code: 'FS_EDIT_NOT_FOUND' }),
        kind('fatal-turn', 40, { plane: 'INFRASTRUCTURE', code: 'SERVER', detail: 'unavailable_error', perKCalls: null }),
        kind('error-plane', 30, { code: 'FS_NOT_FOUND' }),
        ...Array.from({ length: 9 }, (_, i) => kind(`demo-detector-${i}`, 10 - i)),
      ];
      render();
      stub.breakdown.set(rows);
      fixture.detectChanges();

      const lines = Array.from(el.querySelectorAll('.kinds tbody tr'));
      expect(lines).toHaveLength(10);
      expect(lines[0].textContent).toContain('edit-miss');
      expect(lines[0].textContent, 'the category in words').toContain('repeated miss');
      expect(lines[1].textContent, 'a code and its detail').toContain('SERVER / unavailable_error');
      expect(lines[1].querySelector('td.na')?.textContent?.trim(), 'no calls is n/a, never 0').toBe('n/a');
      expect(lines[2].textContent).toContain('FS_NOT_FOUND');
      expect(lines[0].textContent).toContain('6.00');

      // rows 11 and 12 carry 3 + 2 findings
      expect(el.querySelector('.kinds + .code-tail')?.textContent).toContain('+5 findings in other kinds');
    });

    /**
     * One category split by its detail: a shell edit by script, by redirect and in place are three
     * kinds with three counts, and without the detail they read as one kind listed three times.
     */
    it('names the detail beside the category when a detector splits one category by it', () => {
      render();
      stub.breakdown.set([
        kind('shell-edit', 9, { category: 'DIRECT_MUTATION', detail: 'SCRIPT' }),
        kind('shell-edit', 4, { category: 'DIRECT_MUTATION', detail: 'IN_PLACE' }),
      ]);
      fixture.detectChanges();

      const lines = Array.from(el.querySelectorAll('.kinds tbody tr')).map(r => r.textContent ?? '');
      expect(lines[0]).toContain('direct mutation · script');
      expect(lines[1]).toContain('direct mutation · in place');
    });

    it('opens a kind as its detector\'s rows, replacing a code drill-down', () => {
      render();
      stub.breakdown.set([kind('shell-edit', 5, { category: 'DIRECT_MUTATION' })]);
      fixture.detectChanges();

      (el.querySelector('.kinds .kind-open') as HTMLButtonElement).click();
      expect(url.gos).toEqual([{ path: ['/findings'], state: { detector: 'shell-edit', code: null, page: 0 } }]);
    });
  });

  /**
   * On an all-time view "Use All time" navigated to the URL already open, and nothing moved: a
   * button whose only product is nothing teaches the reader the empty state's advice is empty.
   */
  it('does not offer All time on a view that already is all time', () => {
    render({
      tiles: { sessions: 0, findings: 0, toolCalls: 0, steps: 0 },
      planeMix: {}, topDetectors: [], topCodes: [], series: [], throughput: [],
    });
    const labels = Array.from(el.querySelectorAll('.empty button')).map(b => b.textContent?.trim());
    expect(labels).toEqual(['Run indexer']);
  });

  it('says the kinds could not be loaded once the breakdown request has failed', () => {
    render();
    expect(el.querySelector('.kinds')).toBeNull();
    stub.failedLane.set('breakdown');
    fixture.detectChanges();
    const card = Array.from(el.querySelectorAll('.card')).find(c => c.textContent?.includes('Findings by kind'))!;
    expect(card.textContent).toContain('could not be loaded');
    expect(card.textContent).not.toContain('Loading');
  });

  it('says a kind opens all of its detector\'s findings, not only that kind', () => {
    render();
    stub.breakdown.set([{
      detector: 'edit-miss', plane: 'MODEL_MISUSE', category: 'REPEATED_MISS', code: 'FS_EDIT_NOT_FOUND',
      detail: null, count: 60, perKCalls: 6,
    }]);
    fixture.detectChanges();
    const button = el.querySelector('.kind-open')!;
    expect(button.getAttribute('aria-label')).toBe('Open all edit-miss findings (this row counts only repeated miss)');
    expect(button.getAttribute('title')).toBe(button.getAttribute('aria-label'));
  });
});
