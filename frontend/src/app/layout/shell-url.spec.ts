import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Location } from '@angular/common';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FindingsPageDto, OverviewDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { Shell } from './shell';
import { Cohorts } from '../cohorts/cohorts';
import type { CohortPageDto } from '../api/types';

/**
 * The loop the whole URL change rests on: the address bar is upstream of the data. A control
 * navigates, the shell reads the URL back, writes the store and asks for exactly the loads the
 * change needs. Component specs stand this in with a double; this is the one that proves the
 * real thing behaves that way, so the double is not a story the suite tells itself.
 */

const overview: OverviewDto = {
  tiles: { sessions: 12, findings: 40, toolCalls: 300, steps: 90 },
  planeMix: {}, topDetectors: [], topCodes: [], uncodedFindings: 0, series: [], throughput: [],
  vocabulary: {
    schemas: ['V0', 'V3'], models: [], providers: [], roles: [], presets: [], harnessVersions: [],
    codes: ['FS_STALE_VERSION'], detectors: [],
  },
};
const findings: FindingsPageDto = { total: 40, page: 0, size: 20, items: [] };

describe('Shell URL loop', () => {
  let store: InsightsStore;
  let http: HttpTestingController;
  let router: Router;
  let location: Location;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideHttpClient(withFetch()),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
      ],
    }).compileComponents();

    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    location = TestBed.inject(Location);
    // Without this the router never hears popstate, so `location.back()` would move the
    // history and leave the router where it was — which is the very bug this spec is about.
    router.setUpLocationChangeListener();
  });

  /** Answer whatever the shell asked for, so the next navigation starts from a settled state. */
  function settle(): void {
    for (const req of http.match(r => r.url === '/api/overview')) { req.flush(overview); }
    for (const req of http.match(r => r.url === '/api/findings')) { req.flush(findings); }
  }

  async function open(url: string): Promise<void> {
    await router.navigateByUrl(url);
    TestBed.createComponent(Shell).detectChanges();
  }

  it('reads the opening URL into the store and loads from it', async () => {
    await open('/findings?schema=V3&page=2&sort=confidence:asc');

    expect(store.filters().schema).toBe('V3');
    expect(store.page()).toBe(1);
    expect(store.sort()).toEqual({ field: 'confidence', dir: 'asc' });

    // the first request carries what the link said, not the defaults
    const req = http.expectOne(r => r.url === '/api/findings');
    expect(req.request.params.get('schema')).toBe('V3');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('sort')).toBe('confidence:asc');
    settle();
  });

  /**
   * A page change describes the same population as the page before it. Re-asking the overview
   * for it is two wasted requests and a board that flickers for a move nobody made there.
   */
  it('re-asks only the findings when only the page changed', async () => {
    await open('/findings');
    settle();

    await router.navigate([], { queryParams: { page: '3' }, queryParamsHandling: 'merge' });

    expect(http.match(r => r.url === '/api/overview')).toHaveLength(0);
    const req = http.expectOne(r => r.url === '/api/findings');
    expect(req.request.params.get('page')).toBe('2');
    settle();
  });

  it('re-asks everything when the population changed', async () => {
    await open('/findings');
    settle();

    await router.navigate([], { queryParams: { schema: 'V0' }, queryParamsHandling: 'merge' });

    expect(http.match(r => r.url === '/api/overview')).toHaveLength(1);
    expect(http.match(r => r.url === '/api/findings')).toHaveLength(1);
    settle();
  });

  /**
   * The point of the whole change: the back button is a real control again. Before this, the
   * history moved and the screen did not, so the address bar described a view nobody was
   * looking at.
   */
  it('restores the previous view when the history moves back', async () => {
    await open('/findings');
    settle();

    await router.navigate([], { queryParams: { code: 'FS_STALE_VERSION' }, queryParamsHandling: 'merge' });
    expect(store.code()).toBe('FS_STALE_VERSION');
    settle();

    location.back();
    await router.navigated;
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(store.code()).toBeNull();
    settle();
  });

  it('leaves a default view as a bare URL', async () => {
    await open('/findings?page=1&size=20&sort=time:desc');
    settle();

    await router.navigate([], {
      queryParams: { page: null, size: null, sort: null },
      queryParamsHandling: 'merge',
    });

    expect(location.path()).toBe('/findings');
  });
});

/**
 * The same loop with the real cohorts screen in the outlet. The shell used to write a fresh
 * filters object on every URL change, and the cohorts screen watches that signal: a candidate
 * change — a key no population depends on — re-asked the cohort table, and the new table
 * re-asked the judge. Three requests for one verdict; one is what the change needs.
 */
describe('Shell URL loop on the cohorts screen', () => {
  let http: HttpTestingController;
  let router: Router;

  const cohorts: CohortPageDto = {
    groupBy: 'harnessVersion', baseline: '0.1.0', basisNote: null,
    cohorts: ['0.1.0', '0.2.0', '0.3.0'].map(key => ({
      key, sessions: 2, toolCalls: 100, findings: 1, guardFindings: 1, misuseFindings: 0, infraFindings: 0,
      findingsPerKCalls: 10, violationRatePerK: 10, misuseRatePerK: 0, infraRatePerK: 0,
      findingsPerKCallsDelta: 0, violationRatePerKDelta: 0, misuseRatePerKDelta: 0, infraRatePerKDelta: 0,
    })),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideHttpClient(withFetch()),
        provideHttpClientTesting(),
        provideRouter([{ path: 'cohorts', component: Cohorts }, { path: '**', children: [] }]),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  it('asks once for the verdict when only the candidate changes, and not again for the table', async () => {
    await router.navigateByUrl('/cohorts');
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    await fixture.whenStable();
    for (const req of http.match(r => r.url === '/api/overview')) { req.flush(overview); }
    for (const req of http.match(r => r.url === '/api/findings')) { req.flush(findings); }
    http.expectOne(r => r.url === '/api/cohorts').flush(cohorts);
    fixture.detectChanges();
    // three cohorts and no candidate yet: nothing to judge
    expect(http.match(r => r.url === '/api/judge')).toHaveLength(0);

    await router.navigate([], { queryParams: { candidate: '0.2.0' }, queryParamsHandling: 'merge' });
    fixture.detectChanges();
    await fixture.whenStable();

    expect(http.match(r => r.url === '/api/cohorts'), 'the table describes the same selection').toHaveLength(0);
    const judges = http.match(r => r.url === '/api/judge');
    expect(judges).toHaveLength(1);
    expect(judges[0].request.params.get('candidate')).toBe('0.2.0');
    judges[0].flush(null);
    expect(http.match(() => true), 'and nothing else').toHaveLength(0);
  });
});
