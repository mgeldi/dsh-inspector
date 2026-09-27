import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { CohortPageDto, CohortRow } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { ViewUrl } from '../state/view-url';
import { FakeViewUrl } from '../state/view-url.testing';
import { Cohorts } from './cohorts';

// Round-4 regressions for the cohorts screen. Invented values only.
function cohort(key: string): CohortRow {
  return {
    key, sessions: 3, toolCalls: 100, findings: 1, guardFindings: 1, misuseFindings: 0, infraFindings: 0,
    findingsPerKCalls: 10, violationRatePerK: 10, misuseRatePerK: 0, infraRatePerK: 0,
    findingsPerKCallsDelta: 0, violationRatePerKDelta: 0, misuseRatePerKDelta: 0, infraRatePerKDelta: 0,
  };
}
const page = (groupBy: string, baseline: string, keys: string[]): CohortPageDto =>
  ({ groupBy, baseline, basisNote: null, cohorts: keys.map(cohort) });

describe('Cohorts, round 4', () => {
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

  const axisSelect = () => el.querySelector('#group-by') as HTMLSelectElement;

  /**
   * A new axis whose baseline the server refuses leaves the previous axis's table on screen. The
   * Group-by select named the new axis above it — five model rows under "Role". It names the axis
   * of the table that is displayed.
   */
  it('names the axis of the table on screen after a new axis is refused', () => {
    http.expectOne(r => r.url === '/api/cohorts').flush(page('harnessVersion', 'inv-v1', ['inv-v1', 'inv-v2', 'inv-v3']));
    fixture.detectChanges();

    store.cohortGroupBy.set('model');
    store.cohortBaseline.set('inv-gone');
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/cohorts').flush(
      { status: 400, title: 'Unknown filter value', filter: 'baseline', value: 'inv-gone', allowed: [] },
      { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(el.querySelector('tbody tr td.key')?.textContent?.trim(), 'the previous table').toContain('inv-v1');
    expect(axisSelect().value, 'and the axis it is grouped by').toBe('harnessVersion');
    expect(el.querySelector('.crecover'), 'with the way out').toBeTruthy();
  });

  it('states why the table failed, with a way to ask again that is not a refused baseline', () => {
    http.expectOne(r => r.url === '/api/cohorts').flush(
      { status: 503, title: 'Service Unavailable', detail: 'demo index busy' }, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();
    store.dismissError();
    fixture.detectChanges();

    const text = el.querySelector('.cloading')!.textContent!;
    expect(text).toContain('could not be loaded: 503 · demo index busy');
    (el.querySelector('.cloading button') as HTMLButtonElement).click();
    const again = http.expectOne(r => r.url === '/api/cohorts');
    again.flush(page('harnessVersion', 'inv-v1', ['inv-v1']));
    fixture.detectChanges();
    expect(el.querySelector('.cloading')).toBeNull();
  });
});
