import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Location } from '@angular/common';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FindingsPageDto, OverviewDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { Shell } from './shell';

/**
 * The loop the whole URL change rests on: the address bar is upstream of the data. A control
 * navigates, the shell reads the URL back, writes the store and asks for exactly the loads the
 * change needs. Component specs stand this in with a double; this is the one that proves the
 * real thing behaves that way, so the double is not a story the suite tells itself.
 */

const overview: OverviewDto = {
  tiles: { sessions: 12, findings: 40, toolCalls: 300, steps: 90 },
  planeMix: {}, topDetectors: [], topCodes: [], series: [], throughput: [],
  vocabulary: {
    schemas: ['V0', 'V3'], models: [], presets: [], harnessVersions: [],
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
