import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { CohortPageDto, CohortRow, JudgeDto, JudgeRow } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { ViewUrl } from '../state/view-url';
import { FakeViewUrl } from '../state/view-url.testing';
import { CohortJudge, ratioText } from './judge';

// Invented fixture data only — cohort keys, counts and intervals are not from any real corpus.
function cohort(key: string): CohortRow {
  return {
    key, sessions: 6, toolCalls: 1000, findings: 0, guardFindings: 0, misuseFindings: 0, infraFindings: 0,
    findingsPerKCalls: 0, violationRatePerK: 0, misuseRatePerK: 0, infraRatePerK: 0,
    findingsPerKCallsDelta: 0, violationRatePerKDelta: 0, misuseRatePerKDelta: 0, infraRatePerKDelta: 0,
  };
}

function page(keys: string[], baseline = keys[0]): CohortPageDto {
  return { groupBy: 'harnessVersion', baseline, basisNote: null, cohorts: keys.map(cohort) };
}

function row(over: Partial<JudgeRow>): JudgeRow {
  return {
    scope: 'code', key: 'FS_EDIT_NOT_FOUND', baselineCount: 12, candidateCount: 44,
    baselinePerK: 2.4, candidatePerK: 12.12, rateRatio: 5.05, ratioLow: 1.66, ratioHigh: 15.32,
    verdict: 'worse', baselineSessions: 6, candidateSessions: 6, dispersion: 1.8, degreesOfFreedom: 6.2,
    ...over,
  };
}

function judge(baseline: string, candidate: string, rows: JudgeRow[]): JudgeDto {
  return {
    groupBy: 'harnessVersion', baseline, candidate,
    basisNote: 'verdicts read the 95% interval of the rate ratio, not the point estimate',
    baselineCohort: { key: baseline, sessions: 6, toolCalls: 5000 },
    candidateCohort: { key: candidate, sessions: 6, toolCalls: 3630 },
    rows,
  };
}

describe('CohortJudge', () => {
  let fixture: ComponentFixture<CohortJudge>;
  let el: HTMLElement;
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CohortJudge],
      providers: [
        provideHttpClient(withFetch()),
        provideHttpClientTesting(),
        { provide: ViewUrl, useFactory: () => new FakeViewUrl(TestBed.inject(InsightsStore)) },
      ],
    }).compileComponents();
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CohortJudge);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  function show(p: CohortPageDto): void {
    fixture.componentRef.setInput('page', p);
    fixture.detectChanges();
  }

  /**
   * Two cohorts: the candidate is the other one, and the request says so explicitly rather than
   * leaving the backend to infer it — the same request must mean the same thing whatever the
   * selection holds.
   */
  it('judges the only other cohort against the baseline, and prints every row with its interval', () => {
    show(page(['0.1.0', '0.2.0']));

    const req = http.expectOne(r => r.url === '/api/judge');
    expect(req.request.params.get('groupBy')).toBe('harnessVersion');
    expect(req.request.params.get('baseline')).toBe('0.1.0');
    expect(req.request.params.get('candidate')).toBe('0.2.0');
    // The shapes RateRatio.compare produces: a plain ratio; a failure neither side had (no ratio,
    // inconclusive); and one side at zero, where both counts get the Haldane ½ and the ratio is
    // not the quotient of the rates shown beside it.
    req.flush(judge('0.1.0', '0.2.0', [
      row({ scope: 'total', key: 'all', verdict: 'inconclusive', rateRatio: 1.1, ratioLow: 0.8, ratioHigh: 1.5 }),
      row({ scope: 'plane', key: 'MODEL_MISUSE', verdict: 'better', rateRatio: 0.4, ratioLow: 0.2, ratioHigh: 0.8 }),
      row({
        scope: 'plane', key: 'INFRASTRUCTURE', baselineCount: 0, candidateCount: 0, baselinePerK: 0, candidatePerK: 0,
        rateRatio: null, ratioLow: null, ratioHigh: null, verdict: 'inconclusive', baselineSessions: 0, candidateSessions: 0,
      }),
      row({}),
      row({
        key: 'shell-edit', baselineCount: 0, candidateCount: 7, baselinePerK: 0, candidatePerK: 1.93,
        rateRatio: 20.66, ratioLow: 1.18, ratioHigh: 361.2, verdict: 'worse', baselineSessions: 0, candidateSessions: 3,
      }),
    ]));
    fixture.detectChanges();

    const lines = Array.from(el.querySelectorAll('.jtable tbody tr'));
    expect(lines.map(l => l.querySelector('td')!.textContent!.trim()))
      .toEqual(['All findings', 'Model misuse', 'Infrastructure', 'FS_EDIT_NOT_FOUND', 'shell-edit']);

    const code = lines[3].textContent!;
    expect(code).toContain('12 → 44');
    expect(code).toContain('2.40');
    expect(code).toContain('12.12');
    expect(code).toContain('5.05× [1.66–15.32]');
    expect(code).toContain('6 / 6');

    const verdicts = lines.map(l => l.querySelector('.verdict')!);
    expect(verdicts.map(v => v.textContent!.trim())).toEqual(['inconclusive', 'better', 'inconclusive', 'worse', 'worse']);
    expect(verdicts[1].classList, 'better in the accent').toContain('verdict-better');
    expect(verdicts[3].classList, 'worse in the violation hue').toContain('verdict-worse');
    expect(lines[2].textContent, 'a failure neither side had has no ratio: n/a, never 1× or 0×').toContain('n/a');
    expect(lines[2].querySelector('.jflag')?.textContent, 'nothing corrected there').toBe('');

    // one side at zero: the ratio is shown, marked, and the legend says what the mark means
    expect(lines[4].textContent).toContain('20.66× [1.18–361.20]');
    expect(lines[4].querySelector('.jflag')?.getAttribute('title')).toContain('corrected by ½');
    expect(lines[3].querySelector('.jflag')?.textContent, 'an uncorrected ratio is not marked').toBe('');
    expect(el.querySelector('.junits')?.textContent).toContain('corrected by ½ before dividing');

    expect(lines[3].classList, 'the table turns from planes to codes here').toContain('jfirst');
    expect(el.querySelector('.basis-banner')?.textContent).toContain('95% interval');
  });

  /** More than two: the backend would answer a 400, so the screen asks the reader instead. */
  it('asks for a candidate when there is more than one, and judges the one chosen', () => {
    show(page(['0.1.0', '0.2.0', '0.3.0']));
    expect(http.match(r => r.url === '/api/judge'), 'no request without a candidate').toHaveLength(0);
    expect(el.querySelector('.jempty')?.textContent).toContain('Choose the cohort');

    const select = el.querySelector('#candidate') as HTMLSelectElement;
    expect(el.querySelector('label[for="candidate"]'), 'the control is labelled').toBeTruthy();
    expect(Array.from(select.options).filter(o => !o.disabled).map(o => o.value),
      'every cohort but the baseline').toEqual(['0.2.0', '0.3.0']);

    select.value = '0.3.0';
    select.dispatchEvent(new Event('change'));
    expect(store.judgeCandidate(), 'the choice travels in the URL').toBe('0.3.0');
    fixture.detectChanges();

    const req = http.expectOne(r => r.url === '/api/judge');
    expect(req.request.params.get('candidate')).toBe('0.3.0');
    req.flush(judge('0.1.0', '0.3.0', [row({ scope: 'total', key: 'all' })]));
    fixture.detectChanges();
    expect(el.querySelectorAll('.jtable tbody tr')).toHaveLength(1);
  });

  it('says so when the URL names a candidate this selection no longer holds', () => {
    store.judgeCandidate.set('0.9.0');
    show(page(['0.1.0', '0.2.0', '0.3.0']));

    expect(http.match(r => r.url === '/api/judge')).toHaveLength(0);
    expect(el.querySelector('.jempty')?.textContent).toContain('0.9.0 is not in this selection');
  });

  it('has nothing to judge in a one-cohort selection, and says that rather than asking', () => {
    show(page(['0.1.0']));
    expect(http.match(r => r.url === '/api/judge')).toHaveLength(0);
    expect((el.querySelector('#candidate') as HTMLSelectElement).disabled).toBe(true);
    expect(el.querySelector('.jempty')?.textContent).toContain('nothing to judge');
  });

  /**
   * A verdict for another pair must not sit under this table while the new answer is on its way:
   * it would be one comparison's verdict beside another's deltas.
   */
  it('shows no verdict that answers a different pair', () => {
    store.judge.set(judge('0.1.0', '0.3.0', [row({})]));
    show(page(['0.1.0', '0.2.0']));

    expect(el.querySelector('.jtable'), 'the stored verdict is for 0.3.0').toBeNull();
    expect(el.textContent).toContain('Judging…');
    http.expectOne(r => r.url === '/api/judge' && r.params.get('candidate') === '0.2.0')
      .flush(judge('0.1.0', '0.2.0', [row({})]));
    fixture.detectChanges();
    expect(el.querySelector('.jtable')).toBeTruthy();
  });

  /** A side with no observed calls: RateRatio answers NO_DATA with no ratio, and the rates are absent. */
  it('renders a side with no observed calls as no data, with no rates', () => {
    show(page(['0.1.0', '0.2.0']));
    http.expectOne(r => r.url === '/api/judge').flush(judge('0.1.0', '0.2.0', [
      row({
        scope: 'total', key: 'all', candidateCount: 0, candidatePerK: null,
        rateRatio: null, ratioLow: null, ratioHigh: null, verdict: 'no-data', candidateSessions: 0,
      }),
    ]));
    fixture.detectChanges();

    const line = el.querySelector('.jtable tbody tr')!;
    const verdict = line.querySelector('.verdict')!;
    expect(verdict.textContent!.trim()).toBe('no data');
    expect(verdict.classList).toContain('verdict-no-data');
    expect(Array.from(line.querySelectorAll('td.na')).map(td => td.textContent!.trim())).toEqual(['n/a', 'n/a']);
  });

  /**
   * A filter change or a re-index lands a new table for the same pair. The verdict under the old
   * one was computed over the old selection: it used to stay beside the new table while the new
   * request was out — and for good, if that request failed.
   */
  it('drops the previous verdict when the table is re-asked, and says so when the new one fails', () => {
    show(page(['0.1.0', '0.2.0']));
    http.expectOne(r => r.url === '/api/judge').flush(judge('0.1.0', '0.2.0', [row({ scope: 'total', key: 'all' })]));
    fixture.detectChanges();
    expect(el.querySelector('.jtable')).toBeTruthy();

    // the same pair, a new selection
    show(page(['0.1.0', '0.2.0']));
    const again = http.expectOne(r => r.url === '/api/judge');
    expect(el.querySelector('.jtable'), 'no verdict from the previous selection').toBeNull();
    expect(el.textContent).toContain('Judging…');

    again.flush({ status: 500, title: 'Internal error' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(el.querySelector('.jtable')).toBeNull();
    expect(el.textContent).toContain('could not be computed');
    expect(el.textContent).not.toContain('Judging…');
  });

  /**
   * A filter change drops the baseline and keeps the candidate, and the backend can re-pick that
   * very cohort as the baseline. It is a row of the table, so "not in this selection" was false.
   */
  it('says the candidate became the baseline rather than that it is missing', () => {
    store.judgeCandidate.set('0.2.0');
    show(page(['0.1.0', '0.2.0', '0.3.0'], '0.2.0'));

    const note = el.querySelector('.jempty')!.textContent!;
    expect(note).toContain('0.2.0 is the baseline now');
    expect(note).not.toContain('not in this selection');
    expect(http.match(r => r.url === '/api/judge')).toHaveLength(0);
  });
});

describe('ratioText', () => {
  it('prints the ratio with its interval, alone without one, and n/a without a ratio', () => {
    expect(ratioText(row({}))).toBe('5.05× [1.66–15.32]');
    expect(ratioText(row({ ratioLow: null, ratioHigh: null }))).toBe('5.05×');
    expect(ratioText(row({ rateRatio: null }))).toBe('n/a');
    expect(ratioText(row({ rateRatio: 0.26, ratioLow: 0.0004, ratioHigh: 362186344.77 })),
      'bounds past a thousandfold print as inequalities').toBe('0.26× [<0.01–>999]');
  });
});
