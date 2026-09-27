import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { CohortPageDto, CohortRow, JudgeDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { ViewUrl } from '../state/view-url';
import { FakeViewUrl } from '../state/view-url.testing';
import { Cohorts } from './cohorts';

// Invented fixture data only — keys, counts and the note are not from any real corpus.
const NOTE = 'harness_version is inferred for every session (version_inferred=1), not declared by the harness; baseline V3 chosen by highest tool-call count';

/** Per 1,000 observed calls, two decimals, null without calls — the backend's rule. */
function rate(n: number, calls: number): number | null {
  return calls === 0 ? null : Math.round((n / calls) * 100_000) / 100;
}

/**
 * A row whose rates follow from its counts, so the fixtures cannot claim a rate their own
 * counts contradict. `planes` is [guard, misuse, infra]; they sum to the findings total, as
 * every finding sits on exactly one plane. Deltas are filled in by `against`.
 */
function cohort(key: string, sessions: number, toolCalls: number,
                [guard, misuse, infra]: [number, number, number]): CohortRow {
  const findings = guard + misuse + infra;
  return {
    key, sessions, toolCalls, findings,
    guardFindings: guard, misuseFindings: misuse, infraFindings: infra,
    findingsPerKCalls: rate(findings, toolCalls), violationRatePerK: rate(guard, toolCalls),
    misuseRatePerK: rate(misuse, toolCalls), infraRatePerK: rate(infra, toolCalls),
    findingsPerKCallsDelta: null, violationRatePerKDelta: null,
    misuseRatePerKDelta: null, infraRatePerKDelta: null,
  };
}

/** The deltas of every row against the named baseline, as the backend computes them. */
function against(baseline: string, rows: CohortRow[]): CohortRow[] {
  const base = rows.find(r => r.key === baseline)!;
  const d = (a: number | null, b: number | null): number | null =>
    a === null || b === null ? null : Math.round((a - b) * 100) / 100;
  return rows.map(r => ({
    ...r,
    findingsPerKCallsDelta: d(r.findingsPerKCalls, base.findingsPerKCalls),
    violationRatePerKDelta: d(r.violationRatePerK, base.violationRatePerK),
    misuseRatePerKDelta: d(r.misuseRatePerK, base.misuseRatePerK),
    infraRatePerKDelta: d(r.infraRatePerK, base.infraRatePerK),
  }));
}

function page(over: Partial<CohortPageDto> = {}): CohortPageDto {
  return {
    groupBy: 'schema',
    baseline: 'V3',
    basisNote: NOTE,
    cohorts: against('V3', [
      cohort('V0', 8, 22, [3, 4, 1]),
      cohort('V3', 4, 10, [1, 0, 0]),
    ]),
    ...over,
  };
}

describe('Cohorts', () => {
  let fixture: ComponentFixture<Cohorts>;
  let el: HTMLElement;
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Cohorts],
      providers: [
        provideHttpClient(withFetch()),
        provideHttpClientTesting(),
        // The controls navigate now; this closes the loop the shell closes, so the axis and the
        // baseline travel through the URL rules exactly as they do in the app.
        { provide: ViewUrl, useFactory: () => new FakeViewUrl(TestBed.inject(InsightsStore)) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(Cohorts);
    el = fixture.nativeElement as HTMLElement;
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  const fakeUrl = () => TestBed.inject(ViewUrl) as unknown as FakeViewUrl;

  // In-flight store requests are cancelled at TestBed teardown.
  afterEach(() => http.verify({ ignoreCancelled: true }));

  /** The component never fetches itself: the store owns the request, so the test flushes it. */
  function load(p: CohortPageDto): void {
    http.expectOne(r => r.url === '/api/cohorts').flush(p);
    fixture.detectChanges();
    answerJudges();
  }

  /**
   * A table of two cohorts brings the judge under it, which asks for its own verdicts. Those
   * have a spec of their own (judge.spec.ts); here they are answered so nothing is left open.
   */
  function answerJudges(): void {
    for (const req of http.match(r => r.url === '/api/judge')) {
      const p = req.request.params;
      const side = (key: string) => ({ key, sessions: 1, toolCalls: 1 });
      const judge: JudgeDto = {
        groupBy: p.get('groupBy')!, baseline: p.get('baseline')!, candidate: p.get('candidate')!,
        basisNote: null, baselineCohort: side(p.get('baseline')!), candidateCohort: side(p.get('candidate')!),
        rows: [],
      };
      req.flush(judge);
    }
    fixture.detectChanges();
  }

  it('asks the store for the default axis on first render, and renders the rows', () => {
    const req = http.expectOne(r => r.url === '/api/cohorts');
    expect(req.request.params.get('groupBy'), 'default axis').toBe('harnessVersion');
    expect(req.request.params.has('baseline'), 'no baseline on first load — the backend chooses').toBe(false);

    req.flush(page());
    fixture.detectChanges();
    answerJudges();

    const tbody = el.querySelector('.ctable tbody') as HTMLElement;
    expect(tbody.textContent).toContain('V0');
    expect(tbody.textContent).toContain('V3');
    expect(el.querySelector('.baseline-tag'), 'the baseline row is marked in words').toBeTruthy();
  });

  it('renders the basis note as a visible banner rather than dropping it', () => {
    load(page({
      basisNote: 'one-row cohort: harnessVersion is single-valued in this index, so this is a description, not a comparison',
    }));

    const banner = el.querySelector('.basis-banner') as HTMLElement;
    expect(banner, 'the banner is on screen').toBeTruthy();
    expect(banner.textContent).toContain('one-row cohort: harnessVersion is single-valued');
    expect(el.textContent).toContain('description, not a comparison');
  });

  it('shows no banner when the backend sends no note, rather than an empty box', () => {
    load(page({ basisNote: null }));
    expect(el.querySelector('.basis-banner'), 'a null note is absence, not an empty element').toBeNull();
  });

  it('renders a null rate as n/a, never 0', () => {
    load(page({
      baseline: 'orphan',
      cohorts: against('orphan', [cohort('orphan', 2, 0, [0, 0, 0])]),
    }));

    const cells = Array.from(el.querySelectorAll('td.rate'));
    expect(cells.length, 'all findings and the three planes each have a rate').toBe(4);
    for (const cell of cells) {
      expect(cell.textContent?.trim(), 'no observed calls means n/a, never 0').toBe('n/a');
    }
    // The counts are zero measurements and still render 0: only the rates are n/a.
    const counts = Array.from(el.querySelectorAll('td.count')).map(td => td.textContent?.trim());
    expect(counts).toEqual(['2', '0', '0', '0', '0', '0']);
  });

  /**
   * One all-findings rate cannot say which plane moved: a build that trades guard refusals for
   * model misuse nets out to "no change". Each plane carries its own rate and its own delta,
   * under the same sign and colour rules as the total.
   */
  it('gives every plane its own rate and signed, coloured delta', () => {
    load(page({
      baseline: 'V3',
      cohorts: against('V3', [
        cohort('V0', 8, 20, [2, 6, 0]),
        cohort('V3', 4, 20, [2, 2, 1]),
      ]),
    }));

    const groups = Array.from(el.querySelectorAll('th.group-head')).map(th => th.textContent?.trim());
    expect(groups).toEqual(['All findings', 'Guard', 'Model misuse', 'Infrastructure']);

    const v0 = el.querySelector('tbody tr') as HTMLElement;
    const cell = (group: string, kind: string) =>
      v0.querySelector(`td.${kind}[data-group="${group}"]`) as HTMLElement;

    expect(cell('misuse', 'count').textContent?.trim()).toBe('6');
    expect(cell('misuse', 'rate').textContent?.trim()).toBe('300.00');
    expect(cell('misuse', 'delta').textContent?.trim(), 'a regression is signed +').toBe('+200.00');
    expect(cell('misuse', 'delta').classList, 'and painted as one').toContain('delta-up');

    expect(cell('infra', 'delta').textContent?.trim()).toBe('-50.00');
    expect(cell('infra', 'delta').classList, 'an improvement is painted as one').toContain('delta-down');

    expect(cell('guard', 'delta').textContent?.trim(), 'no change is unsigned').toBe('0.00');
    expect(cell('guard', 'delta').classList).toContain('zero');

    // the plane counts are the total split, never a second total
    const planes = ['guard', 'misuse', 'infra'].map(g => Number(cell(g, 'count').textContent));
    expect(planes.reduce((a, b) => a + b, 0)).toBe(Number(cell('all', 'count').textContent));
  });

  it('states the rate basis and labels the deltas in percentage points, with the sign', () => {
    load(page());

    // The table states the units, so a rate and a delta side by side cannot be confused.
    expect(el.textContent).toContain('per 1,000 observed tool calls');
    expect(el.textContent).toContain('percentage points');

    const deltas = Array.from(el.querySelectorAll('td.delta')).map(td => td.textContent?.trim());
    expect(deltas).toContain('+263.64');
    expect(deltas).toContain('+36.36');
    expect(deltas).toContain('0.00');
    // No unsigned non-zero delta: a sign-less number next to a rate reads as a second rate.
    for (const d of deltas) {
      if (d !== null && d !== 'n/a' && d !== '0.00') {
        expect(d.startsWith('+') || d.startsWith('-'), d).toBe(true);
      }
    }
  });

  /**
   * The rail's contract seen from this screen: the request carries the shared filters, and a
   * facet change re-asks. The fake cannot catch the original bug — it answered whatever it was
   * handed, so a server that ignored the parameters looked identical from here. That gap is the
   * reason the backend grew a test that compares the filtered table against a second axis.
   */
  it('re-requests with the shared filters when a facet changes', () => {
    load(page({ groupBy: 'harnessVersion' }));

    store.filters.update(f => ({ ...f, ...{ model: 'demo-flash-8b' } }));
    fixture.detectChanges();

    const req = http.expectOne(
      r => r.url === '/api/cohorts' && r.params.get('model') === 'demo-flash-8b');
    expect(req.request.params.get('groupBy')).toBe('harnessVersion');
    req.flush(page({ groupBy: 'harnessVersion' }));
    fixture.detectChanges();
    answerJudges();
  });

  it('re-requests through the store when groupBy changes', () => {
    load(page());

    const select = el.querySelector('#group-by') as HTMLSelectElement;
    expect(select).toBeTruthy();
    select.value = 'model';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const req = http.expectOne(r => r.url === '/api/cohorts' && r.params.get('groupBy') === 'model');
    req.flush(page({
      groupBy: 'model',
      baseline: 'demo-flash-8b',
      cohorts: against('demo-flash-8b', [cohort('demo-flash-8b', 5, 30, [1, 1, 0])]),
    }));
    fixture.detectChanges();

    expect(el.textContent).toContain('demo-flash-8b');
    expect(store.cohorts()?.groupBy).toBe('model');
    // the axis is in the URL, and the cohort keys of the previous axis are not
    expect(fakeUrl().last()).toEqual({ groupBy: 'model' });
    expect(store.cohortBaseline()).toBeNull();
  });

  it('re-requests with the chosen baseline when it changes', () => {
    const versions = [cohort('0.1.0', 5, 30, [1, 1, 0]), cohort('0.2.0', 2, 10, [0, 0, 1])];
    load(page({ groupBy: 'harnessVersion', baseline: '0.1.0', cohorts: against('0.1.0', versions) }));

    const select = el.querySelector('#baseline') as HTMLSelectElement;
    expect(select).toBeTruthy();
    select.value = '0.2.0';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const req = http.expectOne(
      r => r.url === '/api/cohorts' && r.params.get('baseline') === '0.2.0' && r.params.get('groupBy') === 'harnessVersion');
    // A baseline change returns the same rows with the deltas re-referenced, so the
    // flush keeps the same keys: only the numbers and the marker move.
    req.flush(page({ groupBy: 'harnessVersion', baseline: '0.2.0', cohorts: against('0.2.0', versions) }));
    fixture.detectChanges();
    answerJudges();

    expect(store.cohorts()?.baseline).toBe('0.2.0');
    expect(store.cohortBaseline(), 'the baseline travels in the URL').toBe('0.2.0');
    expect((el.querySelector('tr.baseline-row td.key') as HTMLElement).textContent).toContain('0.2.0');
  });

  /**
   * One model id can serve both the orchestrator and its subagents, so a model cohort can be two
   * populations under one name. Provider and role are the axes that split them, and like every
   * axis they are published to the rail, which dims the matching facet.
   */
  it('offers provider and role as axes and publishes the chosen one to the rail', () => {
    load(page());

    const select = el.querySelector('#group-by') as HTMLSelectElement;
    expect(Array.from(select.options).map(o => o.value)).toEqual(
      ['harnessVersion', 'model', 'provider', 'role', 'schema', 'preset']);

    select.value = 'role';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(store.cohortAxis(), 'the rail learns the axis').toBe('role');
    const req = http.expectOne(r => r.url === '/api/cohorts' && r.params.get('groupBy') === 'role');
    req.flush(page({
      groupBy: 'role',
      baseline: 'orchestrator',
      cohorts: against('orchestrator', [
        cohort('orchestrator', 6, 40, [2, 1, 1]),
        cohort('subagent', 9, 50, [0, 5, 0]),
      ]),
    }));
    fixture.detectChanges();
    answerJudges();
    expect(el.querySelector('.ctable tbody')?.textContent).toContain('subagent');
  });

  /**
   * An empty table under a filter is "nothing matches", not "the index is empty". Telling that
   * reader to run the indexer sends them to fix the one thing that is not broken.
   */
  it('names the filter, not the indexer, when a filtered selection has no cohorts', () => {
    load(page({ baseline: null, basisNote: null, cohorts: [] }));
    expect(el.querySelector('.cempty-title')?.textContent).toContain('index holds no sessions');

    store.filters.update(f => ({ ...f, role: 'subagent' }));
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/cohorts' && r.params.get('role') === 'subagent')
      .flush(page({ baseline: null, basisNote: null, cohorts: [] }));
    fixture.detectChanges();

    const title = el.querySelector('.cempty-title')?.textContent ?? '';
    expect(title).toContain('match the current filters');
    expect(title).not.toContain('index');
  });

  it('drops the candidate when the baseline moves onto it, rather than judge a cohort against itself', () => {
    const versions = [cohort('0.1.0', 5, 30, [1, 1, 0]), cohort('0.2.0', 2, 10, [0, 0, 1]), cohort('0.3.0', 2, 10, [0, 1, 0])];
    store.judgeCandidate.set('0.2.0');
    load(page({ groupBy: 'harnessVersion', baseline: '0.1.0', cohorts: against('0.1.0', versions) }));

    const select = el.querySelector('#baseline') as HTMLSelectElement;
    select.value = '0.2.0';
    select.dispatchEvent(new Event('change'));
    expect(fakeUrl().last()).toEqual({ baseline: '0.2.0', candidate: null });
    expect(store.judgeCandidate()).toBeNull();

    fixture.detectChanges();
    load(page({ groupBy: 'harnessVersion', baseline: '0.2.0', cohorts: against('0.2.0', versions) }));
  });

  /**
   * A filter on the grouping axis would leave one cohort. It is not applied here — the request
   * leaves it out — and it is not cleared either: it is the reader's choice for the other screens.
   */
  it('does not apply a filter on the axis it groups by, and does not clear it', () => {
    load(page());
    store.filters.update(f => ({ ...f, harnessVersion: '0.1.0', schema: 'V3' }));
    fixture.detectChanges();

    const req = http.expectOne(r => r.url === '/api/cohorts');
    expect(req.request.params.get('groupBy')).toBe('harnessVersion');
    expect(req.request.params.has('harnessVersion'), 'the axis facet is left out').toBe(false);
    expect(req.request.params.get('schema'), 'the other facets apply').toBe('V3');
    req.flush(page());
    fixture.detectChanges();
    answerJudges();
    expect(store.filters().harnessVersion, 'still in the store for the other screens').toBe('0.1.0');
  });
});

/**
 * A link, a reload or a tab switch back opens this screen with the axis and baseline already in
 * the store, before the page has arrived — the case the specs above never built, because they
 * create the screen first and change the axis afterwards. The selects used to bind `[value]`,
 * which is applied before their options exist: the screen showed "Harness version" and the first
 * cohort above a table grouped by role against another, and choosing Harness version did nothing.
 */
describe('Cohorts opened on a link', () => {
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Cohorts],
      providers: [
        provideHttpClient(withFetch()),
        provideHttpClientTesting(),
        { provide: ViewUrl, useFactory: () => new FakeViewUrl(TestBed.inject(InsightsStore)) },
      ],
    }).compileComponents();
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('shows the axis and baseline the URL names, and navigates when another axis is chosen', () => {
    // what the shell writes for /cohorts?groupBy=role&baseline=subagent
    store.cohortGroupBy.set('role');
    store.cohortBaseline.set('subagent');
    const fixture = TestBed.createComponent(Cohorts);
    const el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    const req = http.expectOne(r => r.url === '/api/cohorts');
    expect(req.request.params.get('groupBy')).toBe('role');
    expect(req.request.params.get('baseline')).toBe('subagent');
    req.flush(page({
      groupBy: 'role', baseline: 'subagent',
      cohorts: against('subagent', [
        cohort('orchestrator', 6, 40, [2, 1, 1]), cohort('subagent', 9, 50, [0, 5, 0]), cohort('unknown', 2, 10, [0, 1, 0]),
      ]),
    }));
    fixture.detectChanges();

    const axis = el.querySelector('#group-by') as HTMLSelectElement;
    const baseline = el.querySelector('#baseline') as HTMLSelectElement;
    expect(axis.value, 'the axis the table is grouped by').toBe('role');
    expect(baseline.value, 'the baseline the table is read against').toBe('subagent');
    expect(axis.selectedOptions[0].textContent?.trim()).toBe('Role');

    // Harness version is an ordinary choice now, not the option that already looked chosen.
    axis.value = 'harnessVersion';
    axis.dispatchEvent(new Event('change'));
    const fake = TestBed.inject(ViewUrl) as unknown as FakeViewUrl;
    expect(fake.last()).toEqual({ groupBy: 'harnessVersion' });
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/cohorts' && r.params.get('groupBy') === 'harnessVersion')
      .flush(page({ groupBy: 'harnessVersion' }));
    fixture.detectChanges();
    for (const j of http.match(r => r.url === '/api/judge')) { j.flush(null); }
    expect((el.querySelector('#group-by') as HTMLSelectElement).value).toBe('harnessVersion');
  });

  /**
   * A baseline from a shared link, or one a re-index removed, is a 400 on the first load. That
   * used to leave "Loading cohorts…" with no control on the page; the way out is offered now.
   */
  it('offers a way out of a baseline the server refused, before any table has loaded', () => {
    store.cohortBaseline.set('0.9.0');
    const fixture = TestBed.createComponent(Cohorts);
    const el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    http.expectOne(r => r.url === '/api/cohorts').flush(
      { status: 400, title: 'Unknown filter value', filter: 'baseline', value: '0.9.0', allowed: ['0.1.0', '0.2.0'] },
      { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(el.textContent).not.toContain('Loading cohorts');
    const recover = el.querySelector('.crecover') as HTMLElement;
    expect(recover.textContent).toContain('0.9.0');
    (recover.querySelector('button') as HTMLButtonElement).click();
    expect(store.cohortBaseline(), 'the refused baseline is dropped from the URL').toBeNull();

    fixture.detectChanges();
    const again = http.expectOne(r => r.url === '/api/cohorts');
    expect(again.request.params.has('baseline'), 'the backend picks the busiest cohort').toBe(false);
    again.flush(page());
    fixture.detectChanges();
    for (const j of http.match(r => r.url === '/api/judge')) { j.flush(null); }
    expect(el.querySelector('.crecover'), 'the answer to the new request clears it').toBeNull();
  });

  it('says the table could not be loaded when the request failed for another reason', () => {
    const fixture = TestBed.createComponent(Cohorts);
    const el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/cohorts').flush(
      { status: 500, title: 'Internal error' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(el.textContent).toContain('could not be loaded');
    expect(el.textContent).not.toContain('Loading cohorts');
  });
});
