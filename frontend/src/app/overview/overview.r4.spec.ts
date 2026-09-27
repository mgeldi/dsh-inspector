import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InsightsStore } from '../state/insights.store';
import { ViewUrl } from '../state/view-url';
import { FakeViewUrl } from '../state/view-url.testing';
import { Overview } from './overview';
import type { OverviewDto } from '../api/types';

// jsdom has no ResizeObserver or canvas; the chart is not what this spec is about.
if (typeof globalThis.ResizeObserver === 'undefined') {
  (globalThis as { ResizeObserver?: unknown }).ResizeObserver = class { observe(): void {} unobserve(): void {} disconnect(): void {} };
}
vi.mock('echarts/core', () => ({ use: (): void => {}, init: () => ({ setOption(): void {}, resize(): void {}, dispose(): void {} }) }));
vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {} }));
vi.mock('echarts/components', () => ({ GridComponent: {}, LegendComponent: {}, TooltipComponent: {} }));
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }));

/**
 * A failed first load left the Overview blank: no tiles, and "Loading…" only while busy. With the
 * real store, a refused load states its reason and offers to ask again.
 */
describe('Overview, round 4', () => {
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Overview],
      providers: [
        provideHttpClient(withFetch()), provideHttpClientTesting(),
        { provide: ViewUrl, useFactory: () => new FakeViewUrl(TestBed.inject(InsightsStore)) },
      ],
    }).compileComponents();
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('shows why the first load failed, and asks again from there', () => {
    const fixture = TestBed.createComponent(Overview);
    const el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/breakdown').flush([]);

    store.loadOverview();
    http.expectOne(r => r.url === '/api/overview').flush(
      { status: 400, title: 'Unknown filter value', filter: 'schema', value: 'inv-gone', allowed: ['V0'] },
      { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    const text = el.querySelector('.page-fallback')?.textContent ?? '';
    expect(text).toContain("could not be loaded: 400 · Unknown filter value: 'inv-gone' is not a valid schema");
    (el.querySelector('.page-fallback button') as HTMLButtonElement).click();
    expect(http.match(r => r.url === '/api/overview'), 'asked again').toHaveLength(1);
    expect(http.match(r => r.url === '/api/breakdown'), 'with its kinds').toHaveLength(1);
  });
});

const OVERVIEW: OverviewDto = {
  tiles: { sessions: 4, findings: 7, toolCalls: 90, steps: 30 },
  planeMix: { GUARD: 2, MODEL_MISUSE: 4, INFRASTRUCTURE: 1 },
  topDetectors: [{ detector: 'edit-miss', count: 4 }], topCodes: [{ code: 'FS_EDIT_NOT_FOUND', count: 4 }],
  uncodedFindings: 3, series: [], throughput: [],
  vocabulary: { schemas: ['V0'], models: ['inv-model'], providers: [], roles: [], presets: [],
    harnessVersions: [], codes: ['FS_EDIT_NOT_FOUND'], detectors: ['edit-miss'] },
};

/**
 * A reload that failed after the tiles had loaded left them on screen under a rail that no longer
 * described them, and once another screen's success cleared the error bar nothing said they were
 * stale. The tiles go and the reason stays.
 */
describe('Overview, round 5', () => {
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Overview],
      providers: [
        provideHttpClient(withFetch()), provideHttpClientTesting(),
        { provide: ViewUrl, useFactory: () => new FakeViewUrl(TestBed.inject(InsightsStore)) },
      ],
    }).compileComponents();
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('drops loaded tiles when their reload fails, and keeps saying why without the bar', () => {
    const fixture = TestBed.createComponent(Overview);
    const el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.match(r => r.url === '/api/breakdown').forEach(r => r.flush([]));
    store.loadOverview();
    http.expectOne(r => r.url === '/api/overview').flush(OVERVIEW);
    http.match(r => r.url === '/api/breakdown').forEach(r => r.flush([]));
    fixture.detectChanges();
    expect(el.querySelector('.tiles'), 'loaded').toBeTruthy();

    store.loadOverview();
    http.expectOne(r => r.url === '/api/overview').flush(
      { status: 503, title: 'Service Unavailable', detail: 'demo index busy' }, { status: 503, statusText: 'Service Unavailable' });
    http.match(r => r.url === '/api/breakdown').forEach(r => r.flush([]));
    store.dismissError();
    fixture.detectChanges();

    expect(el.querySelector('.tiles'), 'no stale tiles').toBeNull();
    expect(el.querySelector('.page-fallback')?.textContent).toContain('could not be loaded: 503 · demo index busy');
  });
});
