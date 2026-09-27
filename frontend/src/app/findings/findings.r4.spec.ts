import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FindingDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { ViewUrl } from '../state/view-url';
import { FakeViewUrl } from '../state/view-url.testing';
import { Findings } from './findings';

// Round-4 regressions for the findings screen. Invented values only.
const f1: FindingDto = {
  id: 1, sessionId: 'inv-s1', detector: 'inv-det', plane: 'GUARD', category: null, code: 'INV_CODE', detail: null,
  confidence: null, pathHint: 'inv/a.ts', seq: 1, staleSeq: null, causeSeq: null, occurredAt: 0, summary: 'invented',
};
const refused = (filter: string, value: string) => [
  { status: 400, title: 'Unknown filter value', filter, value, allowed: ['inv-det'] },
  { status: 400, statusText: 'Bad Request' },
] as const;

describe('Findings, round 4', () => {
  let fixture: ComponentFixture<Findings>;
  let el: HTMLElement;
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Findings],
      providers: [
        provideHttpClient(withFetch()), provideHttpClientTesting(),
        { provide: ViewUrl, useFactory: () => new FakeViewUrl(TestBed.inject(InsightsStore)) },
      ],
    }).compileComponents();
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Findings);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    store.loadFindings();
    http.expectOne(r => r.url === '/api/findings').flush({ total: 1, page: 0, size: 20, items: [f1] });
    fixture.detectChanges();
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  /**
   * A detector refused after a table was on screen left the previous rows and pager under
   * "the table could not load": rows of a query that is no longer the one on screen.
   */
  it('drops the previous rows when the table it asked for is refused', () => {
    store.detector.set('inv-gone');
    store.loadFindings();
    const [body, init] = refused('detector', 'inv-gone');
    http.expectOne(r => r.url === '/api/findings').flush(body, init);
    fixture.detectChanges();

    expect(el.querySelectorAll('tbody tr.frow'), 'no rows of the previous query').toHaveLength(0);
    expect(el.querySelector('.pager'), 'nor its pager').toBeNull();
    expect(el.querySelector('.floading')?.textContent).toContain("could not be loaded: 400 · Unknown filter value: 'inv-gone' is not a valid detector");
    expect(el.querySelector('.code-filter')?.textContent).toContain('There is no detector inv-gone');
  });

  it('asks again from the failed state', () => {
    store.loadFindings();
    http.expectOne(r => r.url === '/api/findings').flush({ status: 502, title: 'Bad Gateway' }, { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();
    (el.querySelector('.floading button') as HTMLButtonElement).click();
    http.expectOne(r => r.url === '/api/findings').flush({ total: 1, page: 0, size: 20, items: [f1] });
    fixture.detectChanges();
    expect(el.querySelectorAll('tbody tr.frow')).toHaveLength(1);
  });

  /**
   * A failed finding said "the bar above says why" and had no way to close it. It states its own
   * reason — still there after the bar is dismissed — and closes like the panel it replaces.
   */
  it('states why a finding failed, keeps saying it without the bar, and can be closed', () => {
    (el.querySelector('tbody tr.frow') as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/1').flush(
      { status: 404, title: 'Finding not found' }, { status: 404, statusText: 'Not Found' });
    http.expectOne(r => r.url === '/api/findings/1/context').flush(
      { status: 404, title: 'Finding not found' }, { status: 404, statusText: 'Not Found' });
    store.dismissError();
    fixture.detectChanges();

    const pending = el.querySelector('.detail-pending')!;
    expect(pending.textContent).toContain('could not be loaded: 404 · Finding not found');
    expect(pending.textContent).not.toContain('bar above');
    (Array.from(pending.querySelectorAll('button')).find(b => b.textContent?.trim() === 'Close') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.detail-pending'), 'closed').toBeNull();
  });

  it('states why the calls around a finding could not be loaded', () => {
    (el.querySelector('tbody tr.frow') as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/1').flush({ finding: f1, tool: null, evidence: [] });
    http.expectOne(r => r.url === '/api/findings/1/context').flush(
      { status: 500, title: 'Internal error', detail: 'demo context failure' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(el.querySelector('.seq-note')?.textContent).toContain('could not be loaded: 500 · demo context failure');
  });
});
