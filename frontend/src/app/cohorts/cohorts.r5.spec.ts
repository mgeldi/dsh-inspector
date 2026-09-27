import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { CohortPageDto, CohortRow } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { ViewUrl } from '../state/view-url';
import { FakeViewUrl } from '../state/view-url.testing';
import { Cohorts } from './cohorts';

// Round-5 regression for the cohorts screen. Invented values only.
function cohort(key: string): CohortRow {
  return {
    key, sessions: 3, toolCalls: 100, findings: 1, guardFindings: 1, misuseFindings: 0, infraFindings: 0,
    findingsPerKCalls: 10, violationRatePerK: 10, misuseRatePerK: 0, infraRatePerK: 0,
    findingsPerKCallsDelta: 0, violationRatePerKDelta: 0, misuseRatePerKDelta: 0, infraRatePerKDelta: 0,
  };
}
const page = (keys: string[]): CohortPageDto =>
  ({ groupBy: 'harnessVersion', baseline: keys[0], basisNote: null, cohorts: keys.map(cohort) });

describe('Cohorts, round 5', () => {
  let fixture: ComponentFixture<Cohorts>;
  let el: HTMLElement;
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Cohorts],
      providers: [
        provideHttpClient(withFetch()), provideHttpClientTesting(),
        { provide: ViewUrl, useFactory: () => new FakeViewUrl(TestBed.inject(InsightsStore)) },
      ],
    }).compileComponents();
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Cohorts);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  /**
   * A reload that fails after a table has loaded — the rail changed, the server said no — used to
   * leave the old table under the new filters, and once another screen's success cleared the error
   * bar nothing said it was stale. The table goes, and the reason stays.
   */
  it('drops a loaded table when its reload fails, and keeps saying why without the bar', () => {
    http.expectOne(r => r.url === '/api/cohorts').flush(page(['inv-v1', 'inv-v2']));
    fixture.detectChanges();
    http.match(r => r.url === '/api/judge');       // two cohorts ask for a verdict; not this spec's subject
    expect(el.querySelectorAll('tbody tr').length).toBeGreaterThan(0);

    store.loadCohorts('harnessVersion', 'inv-v1');
    http.expectOne(r => r.url === '/api/cohorts').flush(
      { status: 503, title: 'Service Unavailable', detail: 'demo index busy' }, { status: 503, statusText: 'Service Unavailable' });
    store.dismissError();
    fixture.detectChanges();

    expect(el.querySelectorAll('tbody tr').length, 'no stale rows').toBe(0);
    expect(el.querySelector('.cloading')?.textContent).toContain('could not be loaded: 503 · demo index busy');
  });
});
