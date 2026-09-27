import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { CohortPageDto, CohortRow, JudgeDto, JudgeRow } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { ViewUrl } from '../state/view-url';
import { FakeViewUrl } from '../state/view-url.testing';
import { CohortJudge } from './judge';

// Round-4 regressions for the judge. Invented values only.
function cohort(key: string): CohortRow {
  return {
    key, sessions: 3, toolCalls: 100, findings: 1, guardFindings: 1, misuseFindings: 0, infraFindings: 0,
    findingsPerKCalls: 10, violationRatePerK: 10, misuseRatePerK: 0, infraRatePerK: 0,
    findingsPerKCallsDelta: 0, violationRatePerKDelta: 0, misuseRatePerKDelta: 0, infraRatePerKDelta: 0,
  };
}
const page = (baseline: string, keys: string[]): CohortPageDto =>
  ({ groupBy: 'harnessVersion', baseline, basisNote: null, cohorts: keys.map(cohort) });

function row(over: Partial<JudgeRow>): JudgeRow {
  return {
    scope: 'total', key: 'all', baselineCount: 5, candidateCount: 9, baselinePerK: 50, candidatePerK: 90,
    rateRatio: 1.8, ratioLow: 0.6, ratioHigh: 5.4, verdict: 'inconclusive', baselineSessions: 3,
    candidateSessions: 2, dispersion: 5.7, degreesOfFreedom: 6.2, ...over,
  };
}
const judge = (baseline: string, candidate: string, rows: JudgeRow[]): JudgeDto => ({
  groupBy: 'harnessVersion', baseline, candidate, basisNote: null,
  baselineCohort: { key: baseline, sessions: 3, toolCalls: 100 },
  candidateCohort: { key: candidate, sessions: 3, toolCalls: 100 }, rows,
});

describe('CohortJudge, round 4', () => {
  let fixture: ComponentFixture<CohortJudge>;
  let el: HTMLElement;
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CohortJudge],
      providers: [
        provideHttpClient(withFetch()), provideHttpClientTesting(),
        { provide: ViewUrl, useFactory: () => new FakeViewUrl(TestBed.inject(InsightsStore)) },
      ],
    }).compileComponents();
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CohortJudge);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  const show = (p: CohortPageDto) => { fixture.componentRef.setInput('page', p); fixture.detectChanges(); };

  /**
   * Two cohorts, and the URL's candidate is the re-picked baseline. Falling back to "the only
   * other cohort" judged the reverse pair — the old baseline as candidate — with the ratio
   * inverted, better and worse swapped, and no word about it. It asks now, and says why.
   */
  it('never swaps the pair when, of two cohorts, the candidate became the baseline', () => {
    store.judgeCandidate.set('inv-x');
    show(page('inv-x', ['inv-b', 'inv-x']));

    expect(http.match(r => r.url === '/api/judge'), 'no reversed comparison is asked for').toHaveLength(0);
    expect(el.querySelector('.jempty')?.textContent).toContain('inv-x is the baseline now');
    const select = el.querySelector('#candidate') as HTMLSelectElement;
    expect(select.value, 'nothing is chosen for the reader').toBe('');

    select.value = 'inv-b';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const req = http.expectOne(r => r.url === '/api/judge');
    expect(req.request.params.get('baseline')).toBe('inv-x');
    expect(req.request.params.get('candidate')).toBe('inv-b');
    req.flush(judge('inv-x', 'inv-b', [row({})]));
  });

  it('keeps choosing the only other cohort when the URL names none', () => {
    show(page('inv-a', ['inv-a', 'inv-b']));
    expect(http.expectOne(r => r.url === '/api/judge').request.params.get('candidate')).toBe('inv-b');
  });

  /**
   * The failure is stated where the verdicts would be, with its own reason: the error bar is one
   * sentence that any later success clears, so "the bar above says why" could point at nothing.
   */
  it('states its own failure reason, which survives a later success elsewhere', () => {
    show(page('inv-a', ['inv-a', 'inv-b']));
    http.expectOne(r => r.url === '/api/judge').flush(
      { status: 500, title: 'Internal error', detail: 'demo verdict failure' }, { status: 500, statusText: 'Server Error' });
    store.loadBreakdown();
    http.expectOne(r => r.url === '/api/breakdown').flush([]);
    fixture.detectChanges();

    expect(store.error(), 'the later success cleared the bar').toBeNull();
    const text = el.querySelector('.jempty')!.textContent!;
    expect(text).toContain('could not be computed: 500 · demo verdict failure');
    expect(text).not.toContain('bar above');
  });

  it('shows φ and the degrees of freedom under the sessions, and explains both', () => {
    show(page('inv-a', ['inv-a', 'inv-b']));
    http.expectOne(r => r.url === '/api/judge').flush(judge('inv-a', 'inv-b', [
      row({}),
      row({ scope: 'code', key: 'INV_CODE', rateRatio: null, ratioLow: null, ratioHigh: null, dispersion: 1, degreesOfFreedom: null,
        baselineCount: 0, candidateCount: 0, baselinePerK: 0, candidatePerK: 0 }),
    ]));
    fixture.detectChanges();

    const phis = Array.from(el.querySelectorAll('.jphi')).map(p => p.textContent!.trim());
    expect(phis).toEqual(['φ 5.70 · df 6.2', 'φ 1.00']);
    expect(el.querySelector('.jphi')!.getAttribute('title')).toContain('6.2 degrees of freedom');
    expect(el.querySelector('.junits')?.textContent).toContain('Fewer than three contributing sessions');
  });

  /** The ½ slot is on every ratio, filled or empty, so the ratios keep one right edge. */
  it('gives every ratio the same trailing slot, marked only where a side is zero', () => {
    show(page('inv-a', ['inv-a', 'inv-b']));
    http.expectOne(r => r.url === '/api/judge').flush(judge('inv-a', 'inv-b', [
      row({}),
      row({ scope: 'code', key: 'INV_CODE', baselineCount: 0, baselinePerK: 0, rateRatio: 7.1, ratioLow: 1.1, ratioHigh: 44 }),
    ]));
    fixture.detectChanges();

    const flags = Array.from(el.querySelectorAll('.jtable tbody tr')).map(r => r.querySelector('.jflag'));
    expect(flags.every(f => f !== null), 'a slot on every row').toBe(true);
    expect(flags.map(f => f!.textContent)).toEqual(['', '½']);
    expect(flags[0]!.getAttribute('aria-hidden'), 'an empty slot says nothing to a screen reader').toBe('true');
  });
});
