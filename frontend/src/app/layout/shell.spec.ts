import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { FindingsPageDto, OverviewDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { Shell } from './shell';

// Invented test data: the model name and vocabulary are not from any real corpus.
const overview: OverviewDto = {
  tiles: { sessions: 12, findings: 9, toolCalls: 32, steps: 24 },
  planeMix: { GUARD: 4, MODEL_MISUSE: 3, INFRASTRUCTURE: 2 },
  topDetectors: [{ detector: 'error-plane', count: 6 }],
  series: [{ day: '2026-09-01', findings: 3, toolCalls: 16 }],
  throughput: [],
  vocabulary: {
    schemas: ['V0', 'V3'], models: ['demo-flash-8b'], presets: ['unknown', 'smoke'],
    harnessVersions: ['0.1.0', '0.2.0'], codes: [], detectors: [], sessions: [],
  },
};
const emptyFindings: FindingsPageDto = { total: 9, page: 0, size: 20, items: [] };

@Component({
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="fake-findings">findings route</p>`,
})
class FakeFindingsRoute {}

describe('Shell', () => {
  let store: InsightsStore;
  let http: HttpTestingController;
  let fixture: ComponentFixture<Shell>;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([{ path: 'findings', component: FakeFindingsRoute }]),
        provideHttpClient(withFetch()),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Shell);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    // The shell starts the one shared load in its constructor; settle both requests.
    http.expectOne(r => r.url === '/api/overview').flush(overview);
    http.expectOne(r => r.url === '/api/findings').flush(emptyFindings);
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('carries the app name, the three tabs and the index button in the toolbar', () => {
    expect(el.querySelector('.app-name')?.textContent).toContain('DSH Inspector');

    const tabs = Array.from(el.querySelectorAll('.tab'));
    expect(tabs.map(t => t.textContent)).toEqual(['Overview', 'Findings', 'Cohorts']);

    const button = Array.from(el.querySelectorAll('button')).find(b => b.textContent?.includes('Index sessions'));
    expect(button, 'Index sessions button').toBeTruthy();
    expect(el.querySelector('.index-result'), 'no result before the first run').toBeNull();
  });

  it('marks the current route obvious, moves it on navigation, and keeps the rail through it', async () => {
    const router = TestBed.inject(Router);
    const tabs = () => Array.from(el.querySelectorAll('.tab'));

    expect(tabs()[0].classList, 'overview tab active at /').toContain('tab-active');
    expect(tabs()[1].classList, 'findings tab inactive at /').not.toContain('tab-active');

    await router.navigateByUrl('/findings');
    fixture.detectChanges();

    expect(tabs()[1].classList, 'findings tab active at /findings').toContain('tab-active');
    expect(tabs()[0].classList, 'overview tab inactive at /findings').not.toContain('tab-active');

    // The rail is a sibling of the outlet: a route change swaps the outlet, never the rail.
    expect(el.querySelector('app-filter-rail'), 'rail survives the route change').toBeTruthy();
  });

  it('renders a rejected request as a dismissible error bar carrying the store sentence', () => {
    expect(el.querySelector('.error-bar'), 'no error bar while error is null').toBeNull();

    store.setFilters({ schema: 'nope' });
    store.loadOverview();
    http.expectOne(r => r.url === '/api/overview').flush(
      { status: 400, title: 'Unknown filter value', filter: 'schema', value: 'nope', allowed: ['V0', 'V3'] },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();

    const bar = el.querySelector('.error-bar');
    expect(bar, 'error bar').toBeTruthy();
    expect(bar!.textContent).toContain('Unknown filter value');
    expect(bar!.textContent).toContain("'nope' is not a valid schema");
    expect(bar!.textContent).toContain('V0, V3');

    (el.querySelector('.error-dismiss') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.error-bar'), 'error bar after dismiss').toBeNull();
  });

  it('renders the progress bar only while the store is busy, and from its counter', () => {
    // Presence, not a [hidden] attribute: Material's own stylesheet sets display:block on the
    // bar, which overrides the UA rule behind [hidden], so a hidden-marked bar animated over an
    // idle dashboard. jsdom reports the attribute faithfully and the defect anyway — only
    // looking at the page showed it.
    const bar = () => el.querySelector('mat-progress-bar');
    expect(bar(), 'absent when settled').toBeNull();

    store.loadOverview();
    fixture.detectChanges();
    expect(bar(), 'present while a load is in flight').toBeTruthy();

    http.expectOne(r => r.url === '/api/overview').flush(overview);
    fixture.detectChanges();
    expect(bar(), 'gone again once settled').toBeNull();
  });

  it('calls store.reindex from the button and renders the summary as one line', () => {
    const spy = vi.spyOn(store, 'reindex');
    const button = Array.from(el.querySelectorAll('button')).find(b => b.textContent?.includes('Index sessions')) as HTMLButtonElement;
    button.click();
    expect(spy).toHaveBeenCalledTimes(1);

    http.expectOne(r => r.url === '/api/index/run').flush({
      streams: 12, sessions: 11, steps: 22, toolCalls: 32, findings: 9, parseFailures: 0, durationMs: 120,
    });
    http.expectOne(r => r.url === '/api/overview').flush(overview);
    http.expectOne(r => r.url === '/api/findings').flush(emptyFindings);
    fixture.detectChanges();

    expect(el.querySelector('.index-result')?.textContent).toBe('indexed 12 streams, 9 findings in 0.1 s');
  });
});
