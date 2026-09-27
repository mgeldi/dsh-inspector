import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FindingsPageDto, OverviewDto } from '../api/types';
import { Shell } from './shell';

// Invented values only.
const overview: OverviewDto = {
  tiles: { sessions: 1, findings: 1, toolCalls: 1, steps: 1 }, planeMix: {}, topDetectors: [], topCodes: [],
  uncodedFindings: 0, series: [], throughput: [],
  vocabulary: { schemas: [], models: [], providers: [], roles: [], presets: [], harnessVersions: [], codes: [], detectors: [] },
};
const findings: FindingsPageDto = { total: 0, page: 0, size: 20, items: [] };

@Component({ standalone: true, changeDetection: ChangeDetectionStrategy.OnPush, template: '<p>route</p>' })
class FakeRoute {}

/**
 * The pane the routes render into is the element that scrolls, so the router's own scroll
 * restoration never reached it: a drill-down from a scrolled Overview opened Findings with its
 * banner under the toolbar. A new screen starts at its top; a query change keeps the place.
 */
describe('Shell, round 4', () => {
  let http: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideHttpClient(withFetch()), provideHttpClientTesting(),
        provideRouter([{ path: 'findings', component: FakeRoute }, { path: '**', component: FakeRoute }]),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  function settle(): void {
    for (const r of http.match(q => q.url === '/api/overview')) { r.flush(overview); }
    for (const r of http.match(q => q.url === '/api/findings')) { r.flush(findings); }
  }

  it('starts a new screen at the top of its pane, and keeps the place on a query change', async () => {
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    await router.navigateByUrl('/');
    settle();
    const pane = (fixture.nativeElement as HTMLElement).querySelector('.outlet-pane') as HTMLElement;
    // jsdom lays nothing out, so the pane's scroll position is modelled by hand.
    let top = 0;
    Object.defineProperty(pane, 'scrollTop', { configurable: true, get: () => top, set: (v: number) => { top = v; } });

    top = 53;
    await router.navigateByUrl('/findings?detector=inv-det');
    settle();
    expect(top, 'a new screen').toBe(0);

    top = 40;
    await router.navigateByUrl('/findings?detector=inv-det&page=2');
    settle();
    expect(top, 'the same screen, another page').toBe(40);
  });
});
