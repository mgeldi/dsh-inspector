import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FindingContextDto, FindingDetailDto, FindingDto, FindingsPageDto, OverviewDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { Findings } from './findings';
import { ViewUrl } from '../state/view-url';
import { FakeViewUrl } from '../state/view-url.testing';
import { applyPreset, emptyFilters } from '../state/filters';

// Invented fixture data only — session ids, paths and commands are not from any real corpus.
const finding7: FindingDto = {
  id: 7, sessionId: 'demo-session-1', detector: 'stamp-guard', plane: 'GUARD',
  category: 'DIRECT_MUTATION', code: 'FS_STALE_VERSION', detail: null, confidence: 0.9,
  pathHint: 'src/demo/app.ts', seq: 300, staleSeq: 145, causeSeq: 150,
  occurredAt: Date.parse('2026-09-08T13:24:00Z'),
  summary: 'Write refused: the file was stamped at 145 and mutated before the guard re-read it.',
};
const restore8: FindingDto = {
  id: 8, sessionId: 'demo-session-2', detector: 'stamp-guard', plane: 'GUARD',
  category: 'VCS_RESTORE', code: 'FS_VCS_RESTORE', detail: null, confidence: 0.9,
  pathHint: 'src/demo/config.ts', seq: 400, staleSeq: null, causeSeq: null,
  occurredAt: Date.parse('2026-09-09T08:02:00Z'),
  summary: 'The file was restored by the version-control tool; the mutation came from the user, not the model.',
};
const external9: FindingDto = {
  id: 9, sessionId: 'demo-session-3', detector: 'stamp-guard', plane: 'INFRASTRUCTURE',
  category: 'EXTERNAL', code: null, detail: null, confidence: null,
  pathHint: 'src/demo/notes.md', seq: 500, staleSeq: 310, causeSeq: null,
  occurredAt: Date.parse('2026-09-10T17:45:00Z'),
  summary: 'The file changed outside the window: no model-caused mutation could be attributed.',
};

// A finding from a detector that performs no attribution at all: error-plane maps a tool
// error onto a plane and stops. No category, therefore no attribution verdict to report —
// which is a different fact from external9's "a cause was looked for and not found".
const errorPlane10: FindingDto = {
  id: 10, sessionId: 'demo-session-4', detector: 'error-plane', plane: 'MODEL_MISUSE',
  category: null, code: 'FS_NOT_FOUND', detail: null, confidence: null,
  pathHint: 'docs/demo-notes.md', seq: 600, staleSeq: null, causeSeq: null,
  occurredAt: Date.parse('2026-09-11T09:15:00Z'),
  summary: 'read returned FS_NOT_FOUND',
};

// A fatal turn: the typed harness code, and the sub-code parsed from the structured body.
const fatal11: FindingDto = {
  id: 11, sessionId: 'demo-session-5', detector: 'fatal-turn', plane: 'INFRASTRUCTURE',
  category: null, code: 'SERVER', detail: 'unavailable_error', confidence: null,
  pathHint: null, seq: null, staleSeq: null, causeSeq: null,
  occurredAt: Date.parse('2026-09-12T11:30:00Z'),
  summary: 'turn 4 ended in error SERVER (unavailable_error)',
};

// edit-miss: causeSeq is the previous file-tool operation on the path, seq the failed edit.
const miss12: FindingDto = {
  id: 12, sessionId: 'demo-session-6', detector: 'edit-miss', plane: 'MODEL_MISUSE',
  category: 'MISS_AFTER_READ', code: 'FS_EDIT_NOT_FOUND', detail: null, confidence: 0.9,
  pathHint: 'src/demo/widget.ts', seq: 420, staleSeq: null, causeSeq: 410,
  occurredAt: Date.parse('2026-09-13T14:05:00Z'),
  summary: 'widget.ts: edit found no match although the file was read at seq 410',
};

// shell-edit: no code, staleSeq is the file-tool touch that tracked the file, seq the rewrite.
const shell13: FindingDto = {
  id: 13, sessionId: 'demo-session-7', detector: 'shell-edit', plane: 'MODEL_MISUSE',
  category: 'DIRECT_MUTATION', code: null, detail: null, confidence: 0.6,
  pathHint: 'src/demo/widget.ts', seq: 230, staleSeq: 200, causeSeq: null,
  occurredAt: Date.parse('2026-09-14T16:40:00Z'),
  summary: 'widget.ts rewritten from the shell after a file tool had read it at seq 200',
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
  topCodes: [{ code: 'FS_STALE_VERSION', count: 4 }],
  uncodedFindings: 0,
  series: [{ day: '2026-09-08', findings: 3, toolCalls: 10 }],
  throughput: [{ schema: 'V0', timingSource: 'chunk-events', steps: 18, medianDecodeTps: 163.4, medianTtftMs: 563.5 }],
  vocabulary: {
    schemas: [], models: [], providers: [], roles: [], presets: [], harnessVersions: [], codes: [], detectors: [],
  },
};

describe('Findings', () => {
  let fixture: ComponentFixture<Findings>;
  let el: HTMLElement;
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Findings],
      providers: [
        provideHttpClient(withFetch()),
        provideHttpClientTesting(),
        // The controls navigate now; this closes the same loop the shell closes, so these
        // specs keep asserting what a user gets rather than only that a URL was requested.
        { provide: ViewUrl, useFactory: () => new FakeViewUrl(TestBed.inject(InsightsStore)) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(Findings);
    el = fixture.nativeElement as HTMLElement;
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  // In-flight store requests are cancelled at TestBed teardown.
  afterEach(() => http.verify({ ignoreCancelled: true }));

  const prevButton = () => el.querySelector('button[aria-label="Previous page"]') as HTMLButtonElement;
  const nextButton = () => el.querySelector('button[aria-label="Next page"]') as HTMLButtonElement;

  /** A detail opens with the calls around it; answered with a bare window unless a test needs more. */
  function answerContext(id: number, ctx?: FindingContextDto): void {
    http.expectOne(r => r.url === `/api/findings/${id}/context`)
      .flush(ctx ?? { findingId: id, anchorSeq: null, calls: [], findings: [] });
  }

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
    (header.querySelector('button.sort') as HTMLButtonElement).click();
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

    // both directions are still possible on page 2 of 20. Addressed by label rather than by
    // position: the pager grew numbered pages between Prev and Next, and an index-based
    // selector was only ever right by accident.
    expect(prevButton().disabled).toBe(false);
    expect(nextButton().disabled).toBe(false);

    // and next is disabled where there is nothing after the last partial slice
    loadPage({ total: 389, page: 19, size: 20, items: items.slice(0, 9) });
    expect((el.querySelector('.pager-text')!.textContent)).toContain('381–389');
    expect(nextButton().disabled).toBe(true);
  });

  it('lets Next reach the last page when it is a partial slice', () => {
    // 25 findings at 20 per page: page 1 is the partial last slice (items 21–25).
    // The old goPage guard — `(next + 1) * size > total` — blocked exactly this jump,
    // so the button was enabled (canNext) yet the click silently did nothing.
    const firstSlice = Array.from({ length: 20 }, (_, i) => ({ ...finding7, id: i }));
    loadPage({ total: 25, page: 0, size: 20, items: firstSlice });

    expect(nextButton().disabled).toBe(false);
    nextButton().click();
    fixture.detectChanges();

    const req = http.expectOne(r =>
      r.url === '/api/findings' && r.params.get('page') === '1');
    const lastSlice = Array.from({ length: 5 }, (_, i) => ({ ...finding7, id: 20 + i }));
    req.flush({ total: 25, page: 1, size: 20, items: lastSlice });
    fixture.detectChanges();

    expect(el.querySelector('.pager-text')!.textContent).toContain('21–25');
    expect(nextButton().disabled, 'next is disabled at the partial last page').toBe(true);
  });

  /**
   * 455 findings at twenty a page is 23 pages, and Prev/Next alone put the last one
   * twenty-two clicks away. The window is a fixed width with a gap marker, so the buttons
   * do not shift under the pointer as the current page moves through it.
   */
  it('offers a numbered window with the ends always reachable', () => {
    const items = Array.from({ length: 20 }, (_, i) => ({ ...finding7, id: i }));
    loadPage({ total: 455, page: 9, size: 20, items });

    const labels = Array.from(el.querySelectorAll('.pages li'))
      .map(li => li.textContent!.trim());
    // first, gap, the current page and its neighbours, gap, last
    expect(labels).toEqual(['1', '…', '9', '10', '11', '…', '23']);

    const current = el.querySelector('.page-num.current')!;
    expect(current.textContent!.trim()).toBe('10');
    expect(current.getAttribute('aria-current')).toBe('page');

    // the last page is one click away rather than thirteen
    const last = Array.from(el.querySelectorAll('.page-num'))
      .find(b => b.textContent!.trim() === '23') as HTMLButtonElement;
    last.click();
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/findings' && r.params.get('page') === '22')
      .flush({ total: 455, page: 22, size: 20, items: items.slice(0, 15) });
    fixture.detectChanges();
    expect(el.querySelector('.pager-text')!.textContent).toContain('441–455');
  });

  it('numbers every page when they all fit, with no gap marker', () => {
    loadPage({ total: 60, page: 0, size: 20, items: [finding7] });

    expect(Array.from(el.querySelectorAll('.pages li')).map(li => li.textContent!.trim()))
      .toEqual(['1', '2', '3']);
    expect(el.querySelector('.page-gap')).toBeNull();
  });

  /**
   * Page 7 of 23 at twenty rows is not page 7 of 5 at a hundred. Rescaling would land the
   * reader somewhere in the middle of a different slicing of the same data; the start of it
   * is the only position that means the same thing before and after.
   */
  it('returns to the first page when the page size changes', () => {
    const items = Array.from({ length: 20 }, (_, i) => ({ ...finding7, id: i }));
    loadPage({ total: 455, page: 6, size: 20, items });

    const select = el.querySelector('select[aria-label="Rows per page"]') as HTMLSelectElement;
    select.value = '100';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const req = http.expectOne(r => r.url === '/api/findings'
      && r.params.get('size') === '100' && r.params.get('page') === '0');
    req.flush({ total: 455, page: 0, size: 100, items });
    fixture.detectChanges();
    expect(el.querySelector('.pager-text')!.textContent).toContain('1–100');
  });

  it('opens the detail panel with the causal chain as the loudest thing', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [finding7] });

    (el.querySelector('tbody tr.frow') as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/7').flush(detail7);
    answerContext(7);
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
    answerContext(7);
    fixture.detectChanges();
    // the chain seqs identify which finding is on screen; both rows share a detector name
    expect(el.querySelector('app-finding-detail .detail')?.textContent).toContain('refused at 300');

    // no dimming layer may exist over the table
    expect(el.querySelector('.detail-backdrop')).toBeNull();

    // the second row is still reachable while the panel is open
    (rows[1] as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/9').flush(detail9);
    answerContext(9);
    fixture.detectChanges();
    const swapped = el.querySelector('app-finding-detail .detail')?.textContent ?? '';
    expect(swapped).toContain('refused at 500');
    expect(swapped).not.toContain('refused at 300');
  });

  it('shows no evidence section at all for an EXTERNAL finding', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [external9] });

    (el.querySelector('tbody tr.frow') as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/9').flush(detail9);
    answerContext(9);
    fixture.detectChanges();

    const panel = el.querySelector('app-finding-detail .detail') as HTMLElement;
    expect(panel, 'the panel is open').toBeTruthy();
    expect(panel.querySelector('.evidence'), 'no evidence section exists').toBeNull();
    expect(panel.querySelector('.no-evidence')!.textContent)
      .toMatch(/could not be attributed/i);
  });

  /**
   * A fatal turn's typed code says who failed (SERVER); the sub-code says how
   * (unavailable_error). The code column keeps the code whole and puts the sub-code under it,
   * and the panel names both.
   */
  it('shows a sub-code under its code in the table, and beside it in the panel', () => {
    loadPage({ total: 2, page: 0, size: 20, items: [fatal11, finding7] });

    const [fatalRow, guardRow] = Array.from(el.querySelectorAll('tbody tr.frow'));
    const fatalCode = fatalRow.querySelector('td.code')!;
    expect(fatalCode.textContent).toContain('SERVER');
    expect(fatalCode.querySelector('.code-detail')?.textContent?.trim()).toBe('unavailable_error');
    expect(guardRow.querySelector('.code-detail'), 'no sub-code, no empty line').toBeNull();

    (fatalRow as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/11').flush({ finding: fatal11, tool: null, evidence: [] });
    answerContext(11);
    fixture.detectChanges();
    const panel = el.querySelector('app-finding-detail .detail') as HTMLElement;
    expect(panel.querySelector('.code-chip')?.textContent).toBe('SERVER');
    expect(panel.querySelector('.detail-chip')?.textContent).toBe('unavailable_error');
  });

  it('labels the edit-miss categories in words, without the alarm chip', () => {
    const misses = (['REPEATED_MISS', 'MISS_AFTER_EDIT', 'MISS_AFTER_READ', 'MISS_UNREAD'] as const)
      .map((category, i) => ({ ...miss12, id: 20 + i, category }));
    loadPage({ total: 4, page: 0, size: 20, items: misses });

    const chips = Array.from(el.querySelectorAll('tbody .cat')).map(c => c.textContent?.trim());
    expect(chips).toEqual(['repeated miss', 'miss after own edit', 'miss after read', 'miss, file unread']);
    expect(el.querySelector('tbody .cat-mutation'), 'a miss is not a mutation').toBeNull();

    // the confidence is high, and the tip says why without claiming a path match
    const conf = el.querySelector('td.conf')!;
    expect(conf.textContent?.trim()).toBe('high');
    expect(conf.getAttribute('title')).toMatch(/order of file-tool calls/);
  });

  it('draws the chain of an edit miss and of a shell edit in their own words', () => {
    loadPage({ total: 2, page: 0, size: 20, items: [miss12, shell13] });
    const rows = el.querySelectorAll('tbody tr.frow');

    (rows[0] as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/12').flush({ finding: miss12, tool: 'edit', evidence: [] });
    answerContext(12);
    fixture.detectChanges();
    let panel = (el.querySelector('app-finding-detail .detail') as HTMLElement).textContent!;
    expect(panel).toContain('read at 410');
    expect(panel).toContain('edit missed at 420');
    expect(panel, 'a read is not a stamp, a miss is not a refusal').not.toMatch(/stamped at|refused at/);

    (rows[1] as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/13').flush({
      finding: shell13, tool: 'bash',
      evidence: [{ seq: 230, verbClass: 'MUTATING', pathHint: 'src/demo/widget.ts', excerptRedacted: "sed -i 's/a/b/' widget.ts" }],
    });
    answerContext(13);
    fixture.detectChanges();
    const shellPanel = el.querySelector('app-finding-detail .detail') as HTMLElement;
    panel = shellPanel.textContent!;
    expect(panel).toContain('tracked by a file tool at 200');
    expect(panel).toContain('rewritten from the shell at 230');
    expect(panel).not.toMatch(/stamped at|refused at/);
    // the rewrite is evidence like a stamp-guard cause is, in the same section
    expect(shellPanel.querySelector('.evidence')?.textContent).toContain('MUTATING');
  });

  it('names the likely cause and offers the fix when nothing matches', () => {
    store.filters.set(applyPreset(emptyFilters(), '7d', 1_790_000_000_000));
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

  /**
   * The header cells used to be role="button" themselves. That replaced their column-header
   * role, so aria-sort was ignored and a screen reader heard a row of buttons, not a table's
   * columns and which one it was sorted by.
   */
  it('keeps the headers as column headers, with a real button inside each sortable one', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [finding7] });

    const headers = Array.from(el.querySelectorAll('thead th'));
    expect(headers.map(h => h.getAttribute('role')), 'no role overrides the header').toEqual(headers.map(() => null));
    expect(headers.map(h => h.textContent!.trim())).toEqual(
      ['Time', 'Plane', 'Detector', 'Code', 'Category', 'Confidence', 'Path', 'Session']);
    expect(el.querySelectorAll('thead th button.sort')).toHaveLength(6);

    // the state is stated on the sorted column only, as the WAI-ARIA sortable table does it
    expect(el.querySelector('th[data-field="time"]')!.getAttribute('aria-sort')).toBe('descending');
    expect(el.querySelector('th[data-field="plane"]')!.hasAttribute('aria-sort')).toBe(false);
  });

  it('narrows to the detector the overview handed over, says so, and lets it go', () => {
    store.detector.set('edit-miss');
    store.loadFindings();
    const req = http.expectOne(r => r.url === '/api/findings');
    expect(req.request.params.get('detector')).toBe('edit-miss');
    req.flush({ total: 1, page: 0, size: 20, items: [miss12] });
    fixture.detectChanges();

    const banner = el.querySelector('.code-filter')!;
    expect(banner.textContent).toContain('edit-miss');
    (Array.from(banner.querySelectorAll('button')).find(b => b.textContent?.includes('all detectors')) as HTMLButtonElement).click();

    const again = http.expectOne(r => r.url === '/api/findings');
    expect(again.request.params.has('detector')).toBe(false);
    again.flush({ total: 1, page: 0, size: 20, items: [miss12] });
  });

  /**
   * An empty table under a drill-down is "nothing with this code", not "the rail excludes
   * everything" — and the way out has to be on screen, not only in the rail's Clear.
   */
  it('keeps a drill-down stated, and undoable, when it matches nothing', () => {
    store.code.set('FS_NOT_OBSERVED');
    loadPage({ total: 0, page: 0, size: 20, items: [] });

    expect(el.querySelector('.fempty'), 'the empty state').toBeTruthy();
    expect(el.querySelector('.code-filter')?.textContent).toContain('FS_NOT_OBSERVED');
  });

  /**
   * The chain names up to three calls; the sequence is the stream around them, with the chain's
   * own words on the calls it names, and the other findings in the window one click away.
   */
  it('shows the calls around a finding in the chain\'s words, and opens a neighbour from there', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [finding7] });
    (el.querySelector('tbody tr.frow') as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/7').flush(detail7);
    answerContext(7, {
      findingId: 7, anchorSeq: 300,
      calls: [
        { seq: 145, name: 'read', errorCode: null, plane: null, pathHint: 'src/demo/app.ts', durationMs: 3, startedAt: 1, mark: 'stale' },
        { seq: 150, name: 'bash', errorCode: null, plane: null, pathHint: null, durationMs: 40, startedAt: 2, mark: 'cause' },
        { seq: 210, name: 'edit', errorCode: 'FS_EDIT_NOT_FOUND', plane: 'MODEL_MISUSE', pathHint: 'src/demo/app.ts', durationMs: 2, startedAt: 3, mark: null },
        { seq: 300, name: 'write', errorCode: 'FS_STALE_VERSION', plane: 'GUARD', pathHint: 'src/demo/app.ts', durationMs: 2, startedAt: 4, mark: 'finding' },
      ],
      findings: [
        { id: 7, detector: 'stamp-guard', code: 'FS_STALE_VERSION', category: 'DIRECT_MUTATION', seq: 300 },
        { id: 12, detector: 'edit-miss', code: 'FS_EDIT_NOT_FOUND', category: 'MISS_AFTER_READ', seq: 210 },
      ],
    });
    fixture.detectChanges();

    const calls = Array.from(el.querySelectorAll('.seq-call'));
    expect(calls.map(c => c.querySelector('.seq-n')!.textContent!.trim())).toEqual(['145', '150', '210', '300']);
    expect(calls.map(c => c.querySelector('.seq-mark')?.textContent?.trim() ?? null))
      .toEqual(['stamped', 'changed', null, 'refused']);
    expect(calls[2].querySelector('.seq-code')?.textContent).toBe('FS_EDIT_NOT_FOUND');
    expect(calls[3].classList, 'the finding\'s own call').toContain('final');

    // the open finding is the panel; only the other one is offered
    const neighbours = el.querySelectorAll('.seq-open');
    expect(neighbours).toHaveLength(1);
    expect(neighbours[0].getAttribute('aria-label')).toBe('Open finding 12: edit-miss at seq 210');
    (neighbours[0] as HTMLButtonElement).click();
    http.expectOne(r => r.url === '/api/findings/12').flush({ finding: miss12, tool: 'edit', evidence: [] });
    answerContext(12);
    fixture.detectChanges();
    expect(el.querySelector('app-finding-detail .title')?.textContent).toBe('edit-miss');
  });

  it('places a finding without a seq at the last call before it, and says so', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [fatal11] });
    (el.querySelector('tbody tr.frow') as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/11').flush({ finding: fatal11, tool: null, evidence: [] });
    answerContext(11, {
      findingId: 11, anchorSeq: 88,
      calls: [
        { seq: 87, name: 'read', errorCode: null, plane: null, pathHint: null, durationMs: null, startedAt: null, mark: null },
        { seq: 88, name: 'bash', errorCode: null, plane: null, pathHint: null, durationMs: null, startedAt: null, mark: null },
      ],
      findings: [{ id: 11, detector: 'fatal-turn', code: 'SERVER', category: null, seq: null }],
    });
    fixture.detectChanges();

    const marks = Array.from(el.querySelectorAll('.seq-call')).map(c => c.querySelector('.seq-mark')?.textContent?.trim() ?? null);
    expect(marks).toEqual([null, 'last call before it']);
    expect(el.querySelector('.chain'), 'no seq, no chain to draw').toBeNull();
  });

  /** A failed detail is an answer: "Loading finding…" after it was a wait for nothing. */
  it('says a finding could not be loaded once its request has failed', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [finding7] });
    (el.querySelector('tbody tr.frow') as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/7').flush(
      { status: 404, title: 'Not found' }, { status: 404, statusText: 'Not Found' });
    answerContext(7);
    fixture.detectChanges();

    const pending = el.querySelector('.detail-pending')!;
    expect(pending.textContent).toContain('could not be loaded');
    expect(pending.textContent).not.toContain('Loading');
  });

  /**
   * A detector from a stale link (or one a re-index removed) is a 400 before any table exists.
   * The banner used to live inside the loaded table, so the screen said "Loading findings…"
   * with no control to leave it; the banner is above every state now, and says what went wrong.
   */
  it('offers the way out of a detector the server refused, before any table has loaded', () => {
    store.detector.set('gone-detector');
    store.loadFindings();
    http.expectOne(r => r.url === '/api/findings').flush(
      { status: 400, title: 'Unknown filter value', filter: 'detector', value: 'gone-detector', allowed: ['edit-miss'] },
      { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(el.textContent).not.toContain('Loading findings');
    expect(el.textContent).toContain('could not be loaded');
    const banner = el.querySelector('.code-filter')!;
    expect(banner.textContent).toContain('There is no detector gone-detector in this index');
    (Array.from(banner.querySelectorAll('button')).find(b => b.textContent?.includes('all detectors')) as HTMLButtonElement).click();

    const again = http.expectOne(r => r.url === '/api/findings');
    expect(again.request.params.has('detector')).toBe(false);
    again.flush({ total: 1, page: 0, size: 20, items: [finding7] });
    fixture.detectChanges();
    expect(el.querySelector('.code-filter')).toBeNull();
  });

  /**
   * Finding ids are never reused across a re-index, so the open finding and the neighbours in its
   * sequence name rows that are gone; a click on one was a 404. The panel closes instead.
   */
  it('closes the open finding, and its neighbours with it, when a re-index lands', () => {
    loadPage({ total: 1, page: 0, size: 20, items: [finding7] });
    (el.querySelector('tbody tr.frow') as HTMLElement).click();
    http.expectOne(r => r.url === '/api/findings/7').flush(detail7);
    answerContext(7, {
      findingId: 7, anchorSeq: 300,
      calls: [{ seq: 300, name: 'write', errorCode: 'FS_STALE_VERSION', plane: 'GUARD', pathHint: null, durationMs: 1, startedAt: 1, mark: 'finding' }],
      findings: [{ id: 12, detector: 'edit-miss', code: 'FS_EDIT_NOT_FOUND', category: 'MISS_AFTER_READ', seq: 290 }],
    });
    fixture.detectChanges();
    expect(el.querySelector('.seq-open'), 'a neighbour is offered').toBeTruthy();

    store.reindex();
    http.expectOne(r => r.url === '/api/index/run').flush({
      streams: 2, sessions: 2, steps: 4, toolCalls: 8, findings: 3, evidenceRows: 1, pruned: 0, parseFailures: 0, durationMs: 50,
    });
    http.expectOne(r => r.url === '/api/overview').flush(minimalOverview);
    http.expectOne(r => r.url === '/api/findings').flush({ total: 1, page: 0, size: 20, items: [finding7] });
    fixture.detectChanges();

    expect(el.querySelector('app-finding-detail'), 'the panel is closed').toBeNull();
    expect(el.querySelector('.detail-pending'), 'not waiting on an id the index no longer has').toBeNull();
    expect(el.querySelector('.seq-open'), 'and no pre-index id is offered').toBeNull();
    expect(store.context(), 'nothing of the old sequence is kept').toBeNull();
  });

  it('tells the store the drill-downs narrow the screen while it is open', () => {
    expect(store.findingsOpen()).toBe(true);
    fixture.destroy();
    expect(store.findingsOpen()).toBe(false);
  });
});
