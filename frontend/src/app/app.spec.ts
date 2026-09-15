import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { App } from './app';
import { routes } from './app.routes';
import type { OverviewDto } from './api/types';

// jsdom has no ResizeObserver; the chart wrapper only needs the API surface.
class FakeResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
if (typeof globalThis.ResizeObserver === 'undefined') {
  (globalThis as { ResizeObserver?: unknown }).ResizeObserver = FakeResizeObserver;
}

// The Overview route is lazy and real, and it carries the chart: jsdom cannot host a
// canvas, so the charting library is mocked at its four entry points, the way
// chart.spec.ts does.
const echartsMocks = vi.hoisted(() => {
  const charts: Array<{
    option: unknown | null;
    setOption(option: unknown, _replace?: boolean): void;
    resize(): void;
    dispose(): void;
  }> = [];
  const init = () => {
    const chart = {
      option: null as unknown | null,
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

// Invented test data: the model name and session vocabulary are not from any real corpus.
const overview: OverviewDto = {
  tiles: { sessions: 12, findings: 9, toolCalls: 32, steps: 24 },
  planeMix: { GUARD: 4, MODEL_MISUSE: 3, INFRASTRUCTURE: 2 },
  topDetectors: [{ detector: 'error-plane', count: 6 }],
  series: [{ day: '2026-09-01', findings: 3, toolCalls: 16 }],
  throughput: [],
  vocabulary: {
    schemas: ['V0', 'V3'], models: ['demo-flash-8b'], presets: ['smoke'],
    harnessVersions: ['0.1.0'], codes: [], detectors: [], sessions: [],
  },
};

describe('App', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        provideHttpClient(withFetch()),
        provideHttpClientTesting(),
      ],
    })
      .compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('boots the shell at the root route, and the shell starts the one shared load', async () => {
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    fixture.detectChanges();
    await router.navigateByUrl('/');
    fixture.detectChanges();

    // The shell constructor fired loadAll: one overview and one findings request,
    // both through the one filter contract.
    http.expectOne(r => r.url === '/api/overview').flush(overview);
    http.expectOne(r => r.url === '/api/findings').flush({ total: 9, page: 0, size: 20, items: [] });
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.app-name')?.textContent).toContain('DSH Inspector');
    expect(compiled.querySelector('app-filter-rail'), 'filter rail').toBeTruthy();
    // The outlet renders the real Overview: its tiles are fed by the one shared load.
    expect(compiled.querySelector('app-overview'), 'overview screen').toBeTruthy();
    expect(compiled.querySelectorAll('.tile').length).toBe(4);
    expect(compiled.textContent).toContain('sessions in the index');
  });
});
