import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FindingDetailDto, FindingDto, FindingsPageDto, OverviewDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { Findings } from './findings';

// Invented fixture data only — session ids, paths and commands are not from any real corpus.
const finding7: FindingDto = {
  id: 7, sessionId: 'demo-session-1', detector: 'stamp-guard', plane: 'GUARD',
  category: 'DIRECT_MUTATION', code: 'FS_STALE_VERSION', confidence: 0.9,
  pathHint: 'src/demo/app.ts', seq: 300, staleSeq: 145, causeSeq: 150,
  occurredAt: Date.parse('2026-09-08T13:24:00Z'),
  summary: 'Write refused: the file was stamped at 145 and mutated before the guard re-read it.',
};
const restore8: FindingDto = {
  id: 8, sessionId: 'demo-session-2', detector: 'stamp-guard', plane: 'GUARD',
  category: 'VCS_RESTORE', code: 'FS_VCS_RESTORE', confidence: 0.9,
  pathHint: 'src/demo/config.ts', seq: 400, staleSeq: null, causeSeq: null,
  occurredAt: Date.parse('2026-09-09T08:02:00Z'),
  summary: 'The file was restored by the version-control tool; the mutation came from the user, not the model.',
};
const external9: FindingDto = {
  id: 9, sessionId: 'demo-session-3', detector: 'stamp-guard', plane: 'INFRASTRUCTURE',
  category: 'EXTERNAL', code: null, confidence: null,
  pathHint: 'src/demo/notes.md', seq: 500, staleSeq: 310, causeSeq: null,
  occurredAt: Date.parse('2026-09-10T17:45:00Z'),
  summary: 'The file changed outside the window: no model-caused mutation could be attributed.',
};

// A finding from a detector that performs no attribution at all: error-plane maps a tool
// error onto a plane and stops. No category, therefore no attribution verdict to report —
// which is a different fact from external9's "a cause was looked for and not found".
const errorPlane10: FindingDto = {
  id: 10, sessionId: 'demo-session-4', detector: 'error-plane', plane: 'MODEL_MISUSE',
  category: null, code: 'FS_EDIT_NOT_FOUND', confidence: null,
  pathHint: 'docs/demo-notes.md', seq: 600, staleSeq: null, causeSeq: null,
  occurredAt: Date.parse('2026-09-11T09:15:00Z'),
  summary: 'edit returned FS_EDIT_NOT_FOUND',
};

const detail7: FindingDetailDto = {
  finding: finding7,
  tool: 'write',
  evidence: [{ seq: 150, verbClass: 'MUTATING', pathHint: 'src/demo/app.ts', excerptRedacted: 'rm -f src/demo/app.ts' }],
};
const detail9: FindingDetailDto = {
  finding: external9,
  tool: 'write',
  evidence: [],
};

const minimalOverview: OverviewDto = {
  tiles: { sessions: 11, findings: 9, toolCalls: 32, steps: 18 },
  planeMix: { GUARD: 4, MODEL_MISUSE: 3, INFRASTRUCTURE: 2 },
  topDetectors: [{ detector: 'stamp-guard', count: 4 }],
  series: [{ day: '2026-09-08', findings: 3, toolCalls: 10 }],
  throughput: [{ schema: 'V0', timingSource: 'chunk-events', steps: 18, medianDecodeTps: 163.4, medianTtftMs: 563.5 }],
  vocabulary: { schemas: [], models: [], presets: [], harnessVersions: [], codes: [], detectors: [] },
};

describe('Findings', () => {
  let fixture: ComponentFixture<Findings>;
  let el: HTMLElement;
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Findings],
      providers: [provideHttpClient(withFetch()), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(Findings);
    el = fixture.nativeElement as HTMLElement;
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  // In-flight store requests are cancelled at TestBed teardown.
  afterEach(() => http.verify({ ignoreCancelled: true }));

  /** The component never fetches itself: the store owns every request, so the test drives it. */
  function loadPage(page: FindingsPageDto): void {
    store.loadFindings();
    // The matcher receives the raw HttpRequest: the path in .url, the query in .params.
    http.expectOne(r => r.url === '/api/findings').flush(page);
    fixture.detectChanges();
  }

  it('asks the server for the sort rather than sorting the current page', () => {
    loadPage({ total: 3, page: 0, size: 20, items: [finding7, restore8, external9] });

    const header = el.querySelector('th[data-field="confidence"]') as HTMLElement;
    expect(header, 'confidence header present').toBeTruthy();
    header.click();
    fixture.detectChanges();

    const req = http.expectOne(r =>
      r.url === '/api/findings' && r.params.get('sort') === 'confidence:desc');
    req.flush({ total: 3, page: 0, size: 20, items: [finding7, restore8, external9] });
    fixture.detectChanges();

    // the header now carries the sort it asked for: the class, the ARIA sort state and
    // the inline SVG caret all agree on the direction. (The previous assertion pinned a
    // unicode glyph as the direction indicator; the design contract forbids glyph-based
    // direction, so the equivalent fact — "this header shows desc" — is now checked on
    // the aria-sort / data-dir state and the caret's presence.)
    const sorted = el.querySelector('th[data-field="confidence"]');
    expect(sorted?.classList.contains('sorted')).toBe(true);
    expect(sorted?.getAttribute('aria-sort')).toBe('descending');
    expect(sorted?.getAttribute('data-dir')).toBe('desc');
    expect(sorted?.querySelector('svg.caret'), 'the active header paints the SVG caret').toBeTruthy();
  });

  it('renders a null confidence as unattributed, not a dash or zero', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [external9] });

    const cell = el.querySelector('td.conf')!;
    expect(cell.textContent?.trim()).toBe('unattributed');
    expect(el.textContent).not.toContain('0%');
    // the unattributed tier is visually distinct from the measured tiers
    expect(cell.classList.contains('unattributed')).toBe(true);
    // ...and it says a search happened, because for this row one did
    expect(cell.getAttribute('title')).toMatch(/was looked for/i);
  });

  /**
   * A null confidence means two different things and the screen used to tell one story for
   * both. `ErrorPlaneDetector`'s own javadoc says the UI "distinguishes these by detector,
   * not by rendering every null as 'unattributed'" — and the UI rendered every null as
   * 'unattributed', with a tooltip claiming a cause "could not be attributed" for detectors
   * that never attempt attribution. A sentence describing a search that never ran.
   */
  it('distinguishes a detector that found no cause from one that never looks', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [errorPlane10] });

    const cell = el.querySelector('td.conf')!;
    expect(cell.textContent?.trim()).toBe('n/a');
    expect(cell.textContent?.trim()).not.toBe('unattributed');
    // the tooltip must not claim a failed search on a detector that runs none
    expect(cell.getAttribute('title')).not.toMatch(/could not be attributed|was looked for/i);
    expect(cell.getAttribute('title')).toMatch(/does not attribute a cause/i);
  });

  it('marks a vcs-restore row as legitimate work, not a violation', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [restore8] });

    // The row tint is gone — the distinction the guard exists to show now lives in the
    // category chip, which stayed.
    const row = el.querySelector('tbody tr.frow')!;
    expect(row, 'the row renders').toBeTruthy();
    const categoryCell = Array.from(row.querySelectorAll('td')).find(td =>
      td.querySelector('.cat-restore'))!;
    expect(categoryCell, 'the row carries the restore chip').toBeTruthy();
    expect(categoryCell.textContent).toMatch(/legitimate/i);
    expect(row.querySelector('.cat-mutation'), 'no alarm chip on a legitimate row').toBeNull();

    // and a direct mutation keeps the alarm chip the guard exists to show
    loadPage({ total: 1, page: 0, size: 20, items: [finding7] });
    expect(el.querySelector('.cat-mutation'), 'direct mutation keeps its chip').toBeTruthy();
  });

  it('keeps pagination honest: page 2 of 389 shows the right slice bounds', () => {
    const items = Array.from({ length: 20 }, (_, i) => ({ ...finding7, id: 31 + i }));
    loadPage({ total: 389, page: 2, size: 20, items });

    const pager = el.querySelector('.pager-text')!;
    expect(pager.textContent).toContain('41–60');
    expect(pager.textContent).toContain('389');

    // both directions are still possible on page 2 of 20
    const buttons = Array.from(el.querySelectorAll('.pager button')) as HTMLButtonElement[];
    expect(buttons[0].disabled).toBe(false);
    expect(buttons[1].disabled).toBe(false);

    // and next is disabled where there is nothing after the last partial slice
    loadPage({ total: 389, page: 19, size: 20, items: items.slice(0, 9) });
    expect((el.querySelector('.pager-text')!.textContent)).toContain('381–389');
    expect((Array.from(el.querySelectorAll('.pager button')) as HTMLButtonElement[])[1].disabled).toBe(true);
  });

  it('lets Next reach the last page when it is a partial slice', () => {
    // 25 findings at 20 per page: page 1 is the partial last slice (items 21–25).
    // The old goPage guard — `(next + 1) * size > total` — blocked exactly this jump,
    // so the button was enabled (canNext) yet the click silently did nothing.
    const firstSlice = Array.from({ length: 20 }, (_, i) => ({ ...finding7, id: i }));
    loadPage({ total: 25, page: 0, size: 20, items: firstSlice });

    const next = Array.from(el.querySelectorAll('.pager button'))[1] as HTMLButtonElement;
    expect(next.disabled).toBe(false);
    next.click();
    fixture.detectChanges();

    const req = http.expectOne(r =>
      r.url === '/api/findings' && r.params.get('page') === '1');
    const lastSlice = Array.from({ length: 5 }, (_, i) => ({ ...finding7, id: 20 + i }));
    req.flush({ total: 25, page: 1, size: 20, items: lastSlice });
    fixture.detectChanges();

    expect(el.querySelector('.pager-text')!.textContent).toContain('21–25');
    expect(next.disabled, 'next is disabled at the partial last page').toBe(true);
  });

  it('opens the detail panel with the causal chain as the loudest thing', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [finding7] });

    (el.querySelector('tbody tr.frow') as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/7').flush(detail7);
    fixture.detectChanges();

    const panel = el.querySelector('app-finding-detail .detail') as HTMLElement;
    expect(panel, 'the panel is open').toBeTruthy();
    expect(panel.textContent).toContain(finding7.summary);
    // the three labelled steps of §5.3, in order, with the stamped/changed/refused words
    expect(panel.textContent).toContain('stamped at 145');
    expect(panel.textContent).toContain('changed at 150');
    expect(panel.textContent).toContain('refused at 300');
    const chain = panel.querySelectorAll('.chain-step');
    expect(chain.length).toBe(3);
    // and the evidence, the only place command text appears
    expect(panel.querySelector('.evidence')).toBeTruthy();
    expect(panel.textContent).toContain('MUTATING');

    // closing via the panel's own control dismisses it
    (el.querySelector('app-finding-detail button[aria-label="Close detail"]') as HTMLElement).click();
    fixture.detectChanges();
    expect(el.querySelector('app-finding-detail .detail')).toBeNull();
  });

  /**
   * The panel was a modal dressed as a sidebar: `position: fixed` over a dimming backdrop,
   * `role="dialog"`, and the table underneath unclickable. On a screen whose whole purpose is
   * reading findings one after another, that cost a close and a reopen per row. It is a column
   * of the same row now, so a second row can be opened straight from the first.
   */
  it('opens a second finding directly, without closing the first', () => {
    loadPage({ total: 2, page: 0, size: 20, items: [finding7, external9] });

    const rows = el.querySelectorAll('tbody tr.frow');
    (rows[0] as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/7').flush(detail7);
    fixture.detectChanges();
    // the chain seqs identify which finding is on screen; both rows share a detector name
    expect(el.querySelector('app-finding-detail .detail')?.textContent).toContain('refused at 300');

    // no dimming layer may exist over the table
    expect(el.querySelector('.detail-backdrop')).toBeNull();

    // the second row is still reachable while the panel is open
    (rows[1] as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/9').flush(detail9);
    fixture.detectChanges();
    const swapped = el.querySelector('app-finding-detail .detail')?.textContent ?? '';
    expect(swapped).toContain('refused at 500');
    expect(swapped).not.toContain('refused at 300');
  });

  it('shows no evidence section at all for an EXTERNAL finding', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [external9] });

    (el.querySelector('tbody tr.frow') as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/9').flush(detail9);
    fixture.detectChanges();

    const panel = el.querySelector('app-finding-detail .detail') as HTMLElement;
    expect(panel, 'the panel is open').toBeTruthy();
    expect(panel.querySelector('.evidence'), 'no evidence section exists').toBeNull();
    expect(panel.querySelector('.no-evidence')!.textContent)
      .toMatch(/could not be attributed/i);
  });

  it('names the likely cause and offers the fix when nothing matches', () => {
    loadPage({ total: 0, page: 0, size: 20, items: [] });

    const empty = el.querySelector('.fempty')!;
    expect(empty, 'the empty state is visible').toBeTruthy();
    expect(empty.textContent).toMatch(/All time/i);

    (Array.from(empty.querySelectorAll('button'))
      .find(b => b.textContent?.includes('All time')) as HTMLButtonElement).click();
    fixture.detectChanges();

    // widening the range reloads both screens through the one store
    http.expectOne(r => r.url === '/api/overview').flush(minimalOverview);
    http.expectOne(r => r.url === '/api/findings').flush(
      { total: 1, page: 0, size: 20, items: [finding7] });
    fixture.detectChanges();

    expect(el.querySelector('.fempty'), 'the empty state is gone').toBeNull();
    expect(el.querySelector('tr.frow'), 'rows render').toBeTruthy();
  });
});
