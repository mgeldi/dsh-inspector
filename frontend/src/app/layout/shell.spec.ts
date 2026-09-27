import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { FindingsPageDto, OverviewDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { Shell } from './shell';
import { applyPreset } from '../state/filters';

// Invented test data: the model name and vocabulary are not from any real corpus.
const overview: OverviewDto = {
  tiles: { sessions: 12, findings: 9, toolCalls: 32, steps: 24 },
  planeMix: { GUARD: 4, MODEL_MISUSE: 3, INFRASTRUCTURE: 2 },
  topDetectors: [{ detector: 'error-plane', count: 6 }],
  topCodes: [{ code: 'FS_NOT_FOUND', count: 6 }],
  uncodedFindings: 0,
  series: [{ day: '2026-09-01', findings: 3, toolCalls: 16 }],
  throughput: [],
  vocabulary: {
    schemas: ['V0', 'V3'], models: ['demo-flash-8b'], providers: [], roles: [], presets: ['unknown', 'smoke'],
    harnessVersions: ['0.1.0', '0.2.0'], codes: [], detectors: [],
  },
};
const emptyFindings: FindingsPageDto = { total: 9, page: 0, size: 20, items: [] };

@Component({
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="fake-findings">findings route</p>`,
})
class FakeFindingsRoute {}

/** jsdom runs this suite on an opaque origin, where the real accessor is absent. */
const store2 = new Map<string, string>();
const fakeStorage = {
  getItem: (k: string) => store2.get(k) ?? null,
  setItem: (k: string, v: string) => { store2.set(k, v); },
  removeItem: (k: string) => { store2.delete(k); },
  clear: () => store2.clear(),
  key: () => null,
  length: 0,
};

describe('Shell', () => {
  let store: InsightsStore;
  let http: HttpTestingController;
  let fixture: ComponentFixture<Shell>;
  let el: HTMLElement;

  beforeEach(async () => {
    store2.clear();
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true, writable: true, value: fakeStorage,
    });
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

    store.filters.update(f => ({ ...f, ...{ schema: 'nope' } }));
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

  /**
   * The distinction the whole endpoint exists to make readable: a refused duplicate of a run
   * that is already going gets a bar of its own, announced with role=status, worded by the
   * store rather than quoted from the server's log sentence.
   */
  it('renders a refused second index run as a notice bar, not as an error bar', () => {
    store.reindex();
    http.expectOne(r => r.url === '/api/index/run').flush(
      {
        status: 409,
        type: 'urn:dsh-inspector:index-already-running',
        title: 'Index already running',
        detail: 'an index run is already in progress; this one was refused rather than'
          + ' interleaved with the run that is going on',
      },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();

    const bar = el.querySelector('.notice-bar');
    expect(bar, 'notice bar').toBeTruthy();
    expect(bar!.getAttribute('role'), 'an announcement, not an interruption').toBe('status');
    expect(el.querySelector('.error-bar'), 'the error bar stays away').toBeNull();
    expect(bar!.textContent).toContain('An index run is already in progress');
    expect(bar!.textContent, 'the server sentence is for the log, not the toolbar')
      .not.toContain('interleaved');

    (el.querySelector('.notice-dismiss') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.notice-bar'), 'notice bar after dismiss').toBeNull();
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
      streams: 12, sessions: 11, steps: 22, toolCalls: 32, findings: 9, evidenceRows: 3,
      pruned: 0, parseFailures: 0, durationMs: 120,
    });
    http.expectOne(r => r.url === '/api/overview').flush(overview);
    http.expectOne(r => r.url === '/api/findings').flush(emptyFindings);
    fixture.detectChanges();

    expect(el.querySelector('.index-result')?.textContent).toBe('indexed 12 streams, 9 findings in 0.1 s');
  });

  it('names the streams a run discarded, in the singular when it discarded one', () => {
    // A run now makes the index equal to the corpus, so it can throw rows away — and a line
    // that only ever counts what went in would let a whole corpus vanish unnoticed.
    store.reindex();
    http.expectOne(r => r.url === '/api/index/run').flush({
      streams: 11, sessions: 10, steps: 21, toolCalls: 30, findings: 8, evidenceRows: 3,
      pruned: 1, parseFailures: 0, durationMs: 120,
    });
    http.expectOne(r => r.url === '/api/overview').flush(overview);
    http.expectOne(r => r.url === '/api/findings').flush(emptyFindings);
    fixture.detectChanges();

    expect(el.querySelector('.index-result')?.textContent)
      .toBe('indexed 11 streams, 8 findings in 0.1 s, pruned 1 stream');
  });

  it('counts discarded streams in the plural', () => {
    store.reindex();
    http.expectOne(r => r.url === '/api/index/run').flush({
      streams: 9, sessions: 8, steps: 18, toolCalls: 27, findings: 6, pruned: 3, parseFailures: 0, durationMs: 120,
    });
    http.expectOne(r => r.url === '/api/overview').flush(overview);
    http.expectOne(r => r.url === '/api/findings').flush(emptyFindings);
    fixture.detectChanges();

    expect(el.querySelector('.index-result')?.textContent)
      .toBe('indexed 9 streams, 6 findings in 0.1 s, pruned 3 streams');
  });

  /**
   * The rail holds the filters; the URL holds the state. Once that was true the panel became
   * furniture, and 244px of it was coming out of the findings table on every screen where the
   * filters were already set.
   */
  it('folds the rail away and keeps it away for the next visit', () => {
    expect(el.querySelector('#filter-rail'), 'open by default').toBeTruthy();

    const toggle = el.querySelector('.rail-toggle') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(toggle.getAttribute('aria-controls')).toBe('filter-rail');

    toggle.click();
    fixture.detectChanges();

    // Removed, not hidden: a select behind a shut panel must not stay in the tab order.
    expect(el.querySelector('#filter-rail')).toBeNull();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(store2.get('dsh-inspector.rail')).toBe('closed');
  });

  /**
   * The accessor is not merely empty in a private window with site data blocked — reading it
   * throws outright. A dashboard must not fail to start over which panels were last open, so
   * every access is guarded and the default is the open rail.
   */
  it('starts with the rail open when the preference cannot be read at all', () => {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      get() { throw new DOMException('denied', 'SecurityError'); },
    });

    const guarded = TestBed.createComponent(Shell);
    guarded.detectChanges();
    // a second shell starts its own shared load; settle it so afterEach stays meaningful
    for (const req of http.match(() => true)) { req.flush(req.request.url.includes('overview') ? overview : emptyFindings); }
    const railEl = guarded.nativeElement as HTMLElement;

    expect(railEl.querySelector('#filter-rail'), 'open despite the throw').toBeTruthy();
    // and toggling it must not propagate the failure either
    expect(() => (railEl.querySelector('.rail-toggle') as HTMLButtonElement).click()).not.toThrow();
  });

  /**
   * A panel that can be shut must not be able to take the fact of filtering with it. An
   * unexplained short table reads as "there is not much here", which is the same wrong answer
   * as an empty dashboard that means "you typed something wrong".
   */
  it('carries the number of active filters on the toggle, so shutting it hides nothing', () => {
    const toggle = () => el.querySelector('.rail-toggle') as HTMLButtonElement;
    expect(el.querySelector('.rail-badge'), 'nothing to report on an unfiltered view').toBeNull();
    expect(toggle().getAttribute('aria-label')).toBe('Hide filters');

    store.filters.update(f => ({ ...applyPreset(f, '7d', 1_790_000_000_000), schema: 'V0' }));
    store.code.set('FS_STALE_VERSION');
    fixture.detectChanges();

    // the range and the facet: the code drill-down narrows the Findings screen, not this one
    expect(el.querySelector('.rail-badge')!.textContent!.trim()).toBe('2');

    // on the Findings screen it does narrow, and it counts
    store.findingsOpen.set(true);
    fixture.detectChanges();
    expect(el.querySelector('.rail-badge')!.textContent!.trim()).toBe('3');
    expect(toggle().getAttribute('aria-label')).toBe('Hide filters (3 active)');

    toggle().click();
    fixture.detectChanges();
    expect(el.querySelector('.rail-badge')!.textContent!.trim()).toBe('3');
    expect(toggle().getAttribute('aria-label')).toBe('Show filters (3 active)');
    store.findingsOpen.set(false);

    // on the cohorts screen, a facet that is the grouping axis is not applied, and is not counted
    store.cohortAxis.set('schema');
    fixture.detectChanges();
    expect(el.querySelector('.rail-badge')!.textContent!.trim(), 'the range only').toBe('1');
    store.cohortAxis.set(null);
    fixture.detectChanges();

    // writing the signals above does not itself fetch; nothing may be left in flight
    http.verify();
  });

  it('counts provider and role on the toggle like every other facet', () => {
    store.filters.update(f => ({ ...f, provider: 'demo-gateway', role: 'orchestrator' }));
    fixture.detectChanges();
    expect(el.querySelector('.rail-badge')!.textContent!.trim()).toBe('2');

    // a facet cleared to null narrows nothing and is not counted
    store.filters.update(f => ({ ...f, provider: null }));
    fixture.detectChanges();
    expect(el.querySelector('.rail-badge')!.textContent!.trim()).toBe('1');
    http.verify();
  });

  /**
   * The filters live in the query string, and a plain routerLink writes none: every tab switch
   * opened the next screen unfiltered while the rail — which had just been reset from the bare
   * URL — gave no sign that anything had changed. The tabs carry the query with them now.
   */
  it('keeps the query string when a tab is followed, so the filters survive the switch', async () => {
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/findings?schema=V3&range=7d');
    fixture.detectChanges();
    for (const req of http.match(() => true)) {
      req.flush(req.request.url.includes('overview') ? overview : emptyFindings);
    }
    fixture.detectChanges();

    const hrefs = Array.from(el.querySelectorAll('a.tab')).map(a => a.getAttribute('href'));
    expect(hrefs).toEqual(['/?schema=V3&range=7d', '/findings?schema=V3&range=7d', '/cohorts?schema=V3&range=7d']);
  });

  /**
   * The tabs carry the drill-downs to every screen, and on Overview and Cohorts they narrow
   * nothing. Counted there, the badge announced a filter those screens were not applying.
   */
  it('counts a detector drill-down only on the screen it narrows', () => {
    store.detector.set('edit-miss');
    fixture.detectChanges();
    expect(el.querySelector('.rail-badge'), 'on Overview it filters nothing').toBeNull();

    store.findingsOpen.set(true);
    fixture.detectChanges();
    expect(el.querySelector('.rail-badge')!.textContent!.trim()).toBe('1');
    http.verify();
  });
});
