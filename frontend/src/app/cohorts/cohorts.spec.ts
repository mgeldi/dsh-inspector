import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { CohortPageDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { Cohorts } from './cohorts';

// Invented fixture data only — keys, counts and the note are not from any real corpus.
const NOTE = 'harness_version is inferred for every session (version_inferred=1), not declared by the harness; baseline V3 chosen by highest tool-call count';

function page(over: Partial<CohortPageDto> = {}): CohortPageDto {
  return {
    groupBy: 'schema',
    baseline: 'V3',
    basisNote: NOTE,
    cohorts: [
      {
        key: 'V0', sessions: 8, toolCalls: 22, findings: 8, guardFindings: 3,
        findingsPerKCalls: 363.64, violationRatePerK: 136.36,
        findingsPerKCallsDelta: 263.64, violationRatePerKDelta: 36.36,
      },
      {
        key: 'V3', sessions: 4, toolCalls: 10, findings: 1, guardFindings: 1,
        findingsPerKCalls: 100, violationRatePerK: 100,
        findingsPerKCallsDelta: 0, violationRatePerKDelta: 0,
      },
    ],
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
      providers: [provideHttpClient(withFetch()), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(Cohorts);
    el = fixture.nativeElement as HTMLElement;
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  // In-flight store requests are cancelled at TestBed teardown.
  afterEach(() => http.verify({ ignoreCancelled: true }));

  /** The component never fetches itself: the store owns the request, so the test flushes it. */
  function load(p: CohortPageDto): void {
    http.expectOne(r => r.url === '/api/cohorts').flush(p);
    fixture.detectChanges();
  }

  it('asks the store for the default axis on first render, and renders the rows', () => {
    const req = http.expectOne(r => r.url === '/api/cohorts');
    expect(req.request.params.get('groupBy'), 'default axis').toBe('harnessVersion');
    expect(req.request.params.has('baseline'), 'no baseline on first load — the backend chooses').toBe(false);

    req.flush(page());
    fixture.detectChanges();

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
      cohorts: [{
        key: 'orphan', sessions: 2, toolCalls: 0, findings: 0, guardFindings: 0,
        findingsPerKCalls: null, violationRatePerK: null,
        findingsPerKCallsDelta: null, violationRatePerKDelta: null,
      }],
    }));

    const cells = Array.from(el.querySelectorAll('td.rate'));
    expect(cells.length, 'both rate columns are present').toBe(2);
    for (const cell of cells) {
      expect(cell.textContent?.trim(), 'no observed calls means n/a, never 0').toBe('n/a');
    }
    // The counts are zero measurements and still render 0: only the rates are n/a.
    const counts = Array.from(el.querySelectorAll('td.count')).map(td => td.textContent?.trim());
    expect(counts).toEqual(['2', '0', '0', '0']);
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
      cohorts: [{
        key: 'demo-flash-8b', sessions: 5, toolCalls: 30, findings: 2, guardFindings: 1,
        findingsPerKCalls: 66.67, violationRatePerK: 33.33,
        findingsPerKCallsDelta: 0, violationRatePerKDelta: 0,
      }],
    }));
    fixture.detectChanges();

    expect(el.textContent).toContain('demo-flash-8b');
    expect(store.cohorts()?.groupBy).toBe('model');
  });

  it('re-requests with the chosen baseline when it changes', () => {
    load(page({
      groupBy: 'harnessVersion',
      baseline: '0.1.0',
      cohorts: [
        {
          key: '0.1.0', sessions: 5, toolCalls: 30, findings: 2, guardFindings: 1,
          findingsPerKCalls: 66.67, violationRatePerK: 33.33,
          findingsPerKCallsDelta: 0, violationRatePerKDelta: 0,
        },
        {
          key: '0.2.0', sessions: 2, toolCalls: 10, findings: 1, guardFindings: 0,
          findingsPerKCalls: 100, violationRatePerK: 0,
          findingsPerKCallsDelta: 33.33, violationRatePerKDelta: -33.33,
        },
      ],
    }));

    const select = el.querySelector('#baseline') as HTMLSelectElement;
    expect(select).toBeTruthy();
    select.value = '0.2.0';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const req = http.expectOne(
      r => r.url === '/api/cohorts' && r.params.get('baseline') === '0.2.0' && r.params.get('groupBy') === 'harnessVersion');
    // A baseline change returns the same rows with the deltas re-referenced, so the
    // flush keeps the same keys: only the numbers and the marker move.
    req.flush(page({
      groupBy: 'harnessVersion',
      baseline: '0.2.0',
      cohorts: [
        {
          key: '0.1.0', sessions: 5, toolCalls: 30, findings: 2, guardFindings: 1,
          findingsPerKCalls: 66.67, violationRatePerK: 33.33,
          findingsPerKCallsDelta: -33.33, violationRatePerKDelta: 33.33,
        },
        {
          key: '0.2.0', sessions: 2, toolCalls: 10, findings: 1, guardFindings: 0,
          findingsPerKCalls: 100, violationRatePerK: 0,
          findingsPerKCallsDelta: 0, violationRatePerKDelta: 0,
        },
      ],
    }));
    fixture.detectChanges();

    expect(store.cohorts()?.baseline).toBe('0.2.0');
    expect((el.querySelector('tr.baseline-row td.key') as HTMLElement).textContent).toContain('0.2.0');
  });
});
