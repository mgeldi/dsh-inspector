import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FindingContextDto, FindingDto, FindingDetailDto, FindingsPageDto, OverviewDto } from '../api/types';
import { InsightsStore } from './insights.store';
import { applyPreset, FACET_KEYS } from './filters';

// Invented fixture data: the model name, session id and paths are not from any real corpus.
const goodOverview: OverviewDto = {
  tiles: { sessions: 165, findings: 389, toolCalls: 16450, steps: 13733 },
  planeMix: { GUARD: 95, INFRASTRUCTURE: 140, MODEL_MISUSE: 154 },
  topDetectors: [{ detector: 'error-plane', count: 233 }, { detector: 'retry-storm', count: 72 }],
  topCodes: [{ code: 'FS_NOT_OBSERVED', count: 180 }],
  uncodedFindings: 0,
  series: [{ day: '2026-09-01', findings: 46, toolCalls: 1043 }],
  throughput: [
    { schema: 'V0', timingSource: 'chunk-events', steps: 9040, medianDecodeTps: 163.4, medianTtftMs: 563.5 },
  ],
  vocabulary: {
    schemas: ['V0', 'V3'], models: ['demo-flash-8b'], providers: [], roles: [], presets: ['smoke'],
    harnessVersions: ['0.1.0'], codes: ['FS_STALE_VERSION'],
    detectors: ['error-plane', 'retry-storm'],
  },
};

const finding7: FindingDto = {
  id: 7, sessionId: 'demo-session-1', detector: 'error-plane', plane: 'GUARD',
  category: 'DIRECT_MUTATION', code: 'FS_STALE_VERSION', detail: null, confidence: 0.9,
  pathHint: 'src/demo/app.ts', seq: 300, staleSeq: 145, causeSeq: 150,
  occurredAt: 1_790_000_000_000,
  summary: 'Demo finding: the write was refused because the file was stamped before it was read.',
};

const goodFindings: FindingsPageDto = { total: 389, page: 0, size: 20, items: [finding7] };

const goodDetail: FindingDetailDto = { finding: finding7, tool: 'demo-tool', evidence: [] };

const goodContext: FindingContextDto = {
  findingId: 7, anchorSeq: 300,
  calls: [
    { seq: 145, name: 'read', errorCode: null, plane: null, pathHint: 'src/demo/app.ts', durationMs: 4, startedAt: 1, mark: 'stale' },
    { seq: 300, name: 'write', errorCode: 'FS_STALE_VERSION', plane: 'GUARD', pathHint: 'src/demo/app.ts', durationMs: 3, startedAt: 2, mark: 'finding' },
  ],
  findings: [{ id: 7, detector: 'stamp-guard', code: 'FS_STALE_VERSION', category: 'DIRECT_MUTATION', seq: 300 }],
};

describe('InsightsStore', () => {
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
  });

  // ignoreCancelled: the destroy test intentionally leaves a cancelled in-flight request.
  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('sends the same filter params to overview and findings from the one filter state', () => {
    store.filters.update(f => applyPreset(f, '7d', 1_790_000_000_000));
    store.filters.update(f => ({ ...f, ...{ schema: 'V0', role: 'subagent' } }));
    store.loadOverview();
    store.loadFindings();

    const overviewReq = http.expectOne(r => r.url === '/api/overview');
    const findingsReq = http.expectOne(r => r.url === '/api/findings');

    for (const key of ['from', 'to', ...FACET_KEYS]) {
      expect(findingsReq.request.params.get(key), key).toBe(overviewReq.request.params.get(key));
    }
    expect(overviewReq.request.params.get('to')).toBe('1790000000000');
    expect(overviewReq.request.params.get('from')).toBe('1789395200000');
    expect(overviewReq.request.params.get('schema')).toBe('V0');
    expect(overviewReq.request.params.get('role')).toBe('subagent');

    overviewReq.flush(goodOverview);
    findingsReq.flush(goodFindings);
  });

  it('sends neither from nor to with the default all preset — absence, not empty string', () => {
    store.loadOverview();
    const req = http.expectOne(r => r.url === '/api/overview');
    expect(req.request.params.has('from')).toBe(false);
    expect(req.request.params.has('to')).toBe(false);
    req.flush(goodOverview);
  });

  it('lets a facet be cleared to null, and a null facet is not sent', () => {
    store.filters.update(f => ({ ...f, ...{ schema: 'V0' } }));
    store.filters.update(f => ({ ...f, ...{ schema: null } }));
    expect(store.filters().schema).toBeNull();
    store.loadOverview();
    const req = http.expectOne(r => r.url === '/api/overview');
    expect(req.request.params.has('schema')).toBe(false);
    req.flush(goodOverview);
  });

  it('keeps showing yesterday\'s numbers when today\'s request is rejected', () => {
    store.loadOverview();
    http.expectOne(r => r.url === '/api/overview').flush(goodOverview);
    expect(store.overview()?.tiles.sessions).toBe(165);

    store.filters.update(f => ({ ...f, ...{ schema: 'nope' } }));
    store.loadOverview();
    http.expectOne(r => r.url === '/api/overview').flush(
      { status: 400, title: 'Unknown filter value', filter: 'schema', value: 'nope', allowed: ['V0', 'V3'] },
      { status: 400, statusText: 'Bad Request' });

    expect(store.overview()?.tiles.sessions).toBe(165);
    expect(store.error()).toContain('V0');
  });

  it('keeps the old findings page when the reload is rejected, and names the allowed values', () => {
    store.loadFindings();
    http.expectOne(r => r.url === '/api/findings').flush(goodFindings);
    expect(store.findings()?.total).toBe(389);

    store.filters.update(f => ({ ...f, ...{ schema: 'nope' } }));
    store.loadFindings();
    http.expectOne(r => r.url === '/api/findings').flush(
      { status: 400, title: 'Unknown filter value', filter: 'schema', value: 'nope', allowed: ['V0', 'V3'] },
      { status: 400, statusText: 'Bad Request' });

    expect(store.findings()?.total).toBe(389);
    expect(store.findings()?.items[0].id).toBe(7);
    expect(store.error()).toContain('V0');
    expect(store.error()).toContain('V3');
  });

  /**
   * A 409 from the index endpoint means the server is already doing the thing that was just
   * asked for. Red bar would be a lie — nothing went wrong — and a red bar people learn to
   * ignore is worse than no bar.
   */
  it('reports a refused second index run as a notice, not as an error', () => {
    store.reindex();
    http.expectOne(r => r.url === '/api/index/run').flush(
      {
        status: 409,
        type: 'urn:dsh-inspector:index-already-running',
        title: 'Index already running',
        detail: 'an index run is already in progress; this one was refused rather than'
          + ' interleaved with the run that is going on',
      },
      { status: 409, statusText: 'Conflict' });

    expect(store.notice(), 'notice').toContain('already in progress');
    expect(store.error(), 'error stays empty').toBeNull();
    expect(store.lastIndex(), 'a refused run produced no summary').toBeNull();
    // The button describes its own work: this request is over, whatever the other one is doing.
    expect(store.indexing()).toBe(false);
  });

  it('still calls a genuine index failure an error, and clears the notice on a later success', () => {
    store.reindex();
    http.expectOne(r => r.url === '/api/index/run').flush(
      { status: 409, type: 'urn:dsh-inspector:index-already-running', title: 'Index already running' },
      { status: 409, statusText: 'Conflict' });
    expect(store.notice()).toBeTruthy();

    store.reindex();
    http.expectOne(r => r.url === '/api/index/run').flush(
      { status: 500, type: 'urn:dsh-inspector:bad-request', title: 'Internal error', detail: 'disk went away' },
      { status: 500, statusText: 'Server Error' });

    expect(store.error()).toContain('disk went away');
    expect(store.notice(), 'the notice does not survive a later request').toBeNull();
  });

  it('treats busy as a counter, so the earlier completion does not clear the later spinner', () => {
    store.loadOverview();
    store.loadFindings();
    expect(store.busy()).toBe(2);

    http.expectOne(r => r.url === '/api/overview').flush(goodOverview);
    expect(store.busy()).toBe(1); // overview done; findings still in flight
    expect(store.overview()?.tiles.sessions).toBe(165);

    http.expectOne(r => r.url === '/api/findings').flush(goodFindings);
    expect(store.busy()).toBe(0);
  });

  it('fetches the detail and its sequence lazily and caches both per id', () => {
    expect(store.detail()).toBeNull();

    store.selectFinding(7);
    expect(store.detail()).toBeNull(); // lazy: nothing is set before the response
    http.expectOne(r => r.url === '/api/findings/7').flush(goodDetail);
    const ctx = http.expectOne(r => r.url === '/api/findings/7/context');
    expect(ctx.request.params.get('window'), 'the API default, sent explicitly').toBe('12');
    expect(ctx.request.params.has('schema'), 'the window is a fact of the stream, not of the rail').toBe(false);
    ctx.flush(goodContext);
    expect(store.detail()?.finding.id).toBe(7);
    expect(store.context()?.findingId).toBe(7);

    store.selectFinding(7);
    expect(store.detail()?.finding.id).toBe(7);
    expect(http.match(() => true)).toHaveLength(0); // the second open hit both caches
  });

  it('keeps the evidence when the sequence fails to arrive', () => {
    store.selectFinding(7);
    http.expectOne(r => r.url === '/api/findings/7').flush(goodDetail);
    http.expectOne(r => r.url === '/api/findings/7/context').flush(
      { status: 404, title: 'Not found' }, { status: 404, statusText: 'Not Found' });

    expect(store.detail()?.finding.id, 'the two load independently').toBe(7);
    expect(store.context()).toBeNull();
    expect(store.contextFailed(), 'the failure is named, so the panel stops saying Loading').toBe(7);
  });

  it('lands only the newest open, so an older answer cannot replace the one on screen', () => {
    store.selectFinding(7);
    const firstDetail = http.expectOne(r => r.url === '/api/findings/7');
    const firstContext = http.expectOne(r => r.url === '/api/findings/7/context');
    store.selectFinding(8);
    http.expectOne(r => r.url === '/api/findings/8').flush({ ...goodDetail, finding: { ...finding7, id: 8 } });
    http.expectOne(r => r.url === '/api/findings/8/context').flush({ ...goodContext, findingId: 8 });

    // the first open's answers arrive last
    firstDetail.flush(goodDetail);
    firstContext.flush(goodContext);

    expect(store.detail()?.finding.id).toBe(8);
    expect(store.context()?.findingId).toBe(8);
  });

  it('invalidates the detail cache on any reload, so a re-indexed corpus is not served stale', () => {
    store.selectFinding(7);
    http.expectOne(r => r.url === '/api/findings/7').flush(goodDetail);
    http.expectOne(r => r.url === '/api/findings/7/context').flush(goodContext);
    store.selectFinding(7);
    expect(http.match(() => true)).toHaveLength(0); // cached

    store.loadFindings();
    http.expectOne(r => r.url === '/api/findings').flush(goodFindings);

    store.selectFinding(7);
    http.expectOne(r => r.url === '/api/findings/7').flush(goodDetail); // cache gone, refetched
    http.expectOne(r => r.url === '/api/findings/7/context').flush(goodContext);
  });

  /**
   * Grouping by a facet asks for every value of it side by side. A filter on that facet would
   * leave one cohort — so the cohort and judge requests leave it out, and the filter stays in
   * the store for the screens that do apply it.
   */
  it('leaves the grouping axis out of the cohort and judge requests, and only there', () => {
    store.filters.update(f => ({ ...f, role: 'subagent', schema: 'V0' }));

    store.loadCohorts('role');
    const cohorts = http.expectOne(r => r.url === '/api/cohorts');
    expect(cohorts.request.params.get('groupBy')).toBe('role');
    expect(cohorts.request.params.has('role'), 'the axis is not a filter here').toBe(false);
    expect(cohorts.request.params.get('schema'), 'the other facets still apply').toBe('V0');
    cohorts.flush({ groupBy: 'role', baseline: 'orchestrator', basisNote: null, cohorts: [] });

    store.loadJudge('role', 'orchestrator', 'subagent');
    const judge = http.expectOne(r => r.url === '/api/judge');
    expect(judge.request.params.get('baseline')).toBe('orchestrator');
    expect(judge.request.params.get('candidate')).toBe('subagent');
    expect(judge.request.params.has('role')).toBe(false);
    expect(judge.request.params.get('schema')).toBe('V0');
    judge.flush(null);

    expect(store.filters().role, 'the reader\'s filter is untouched').toBe('subagent');
    store.loadOverview();
    const overview = http.expectOne(r => r.url === '/api/overview');
    expect(overview.request.params.get('role')).toBe('subagent');
    overview.flush(goodOverview);
  });

  it('asks the breakdown over the shared filters', () => {
    store.filters.update(f => ({ ...f, provider: 'demo-gateway' }));
    store.loadBreakdown();
    const req = http.expectOne(r => r.url === '/api/breakdown');
    expect(req.request.params.get('provider')).toBe('demo-gateway');
    req.flush([{ detector: 'edit-miss', plane: 'MODEL_MISUSE', category: 'REPEATED_MISS', code: 'FS_EDIT_NOT_FOUND', detail: null, count: 3, perKCalls: 1.5 }]);
    expect(store.breakdown()?.[0].count).toBe(3);
  });

  it('reindex posts, reloads overview and findings, and exposes the returned counts for a status line', () => {
    store.reindex();
    const post = http.expectOne(r => r.url === '/api/index/run');
    expect(post.request.method).toBe('POST');
    post.flush({
      streams: 168, sessions: 165, steps: 13733, toolCalls: 16450, findings: 389,
      evidenceRows: 148, pruned: 0, parseFailures: 0, durationMs: 4300,
    });

    http.expectOne(r => r.url === '/api/overview').flush(goodOverview);
    http.expectOne(r => r.url === '/api/findings').flush(goodFindings);

    expect(store.lastIndex()?.streams).toBe(168);
    expect(store.lastIndex()?.findings).toBe(389);
    expect(store.lastIndex()?.durationMs).toBe(4300);
    expect(store.overview()?.tiles.sessions).toBe(165);
    expect(store.findings()?.total).toBe(389);
  });

  it('completes its subscriptions on destroy, so a late response cannot touch the state', () => {
    store.loadOverview();
    const pending = http.expectOne(r => r.url === '/api/overview');
    TestBed.resetTestingModule(); // destroys the root injector; the store's DestroyRef fires
    // The in-flight request is unsubscribed by the destroy ref: a late response is
    // cancelled before it can be delivered, and the state was never written.
    expect(pending.cancelled).toBe(true);
    expect(store.overview()).toBeNull();
  });

  /**
   * Two quick page clicks put two findings requests on the wire, and nothing makes the answers
   * come back in order. The slower, older one used to land last: the table showed page 2 under
   * a URL and a pager that said page 3.
   */
  it('lets the newest request of a screen win when answers arrive out of order', () => {
    store.page.set(1);
    store.loadFindings();
    store.page.set(2);
    store.loadFindings();
    const [older, newer] = http.match(r => r.url === '/api/findings');

    newer.flush({ ...goodFindings, page: 2 });
    older.flush({ ...goodFindings, page: 1 });

    expect(store.findings()?.page, 'the view the URL asked for last').toBe(2);
    expect(store.busy(), 'the superseded answer still settles the spinner').toBe(0);
  });

  it('raises no error bar for a request that a newer one already replaced', () => {
    store.loadOverview();
    store.loadOverview();
    const [older, newer] = http.match(r => r.url === '/api/overview');

    newer.flush(goodOverview);
    older.flush(
      { status: 400, title: 'Unknown filter value', filter: 'schema', value: 'old', allowed: ['V0'] },
      { status: 400, statusText: 'Bad Request' },
    );

    expect(store.error(), 'the screen shows the answer to the current request').toBeNull();
    expect(store.overview()?.tiles.sessions).toBe(165);
    expect(store.busy()).toBe(0);
  });

  /**
   * A screen waiting on a lane must stop saying "Loading…" once the answer has come back as an
   * error — and a value the server refused by name is what the screen offers a way out of.
   */
  it('marks a lane failed, with the value the server refused, until the lane asks again', () => {
    store.detector.set('gone-detector');
    store.loadFindings();
    http.expectOne(r => r.url === '/api/findings').flush(
      { status: 400, title: 'Unknown filter value', filter: 'detector', value: 'gone-detector', allowed: ['edit-miss'] },
      { status: 400, statusText: 'Bad Request' });

    expect(store.failed('findings')).toBe(true);
    expect(store.failed('overview'), 'only the lane that failed').toBe(false);
    expect(store.rejected()).toEqual({ lane: 'findings', filter: 'detector', value: 'gone-detector' });

    store.detector.set(null);
    store.loadFindings();
    expect(store.failed('findings'), 'a new request is not failed yet').toBe(false);
    expect(store.rejected(), 'and the refusal answered the previous one').toBeNull();
    http.expectOne(r => r.url === '/api/findings').flush(goodFindings);
  });

  it('drops the previous verdict when the judge asks again', () => {
    store.judge.set({
      groupBy: 'harnessVersion', baseline: '0.1.0', candidate: '0.2.0', basisNote: null,
      baselineCohort: { key: '0.1.0', sessions: 1, toolCalls: 1 },
      candidateCohort: { key: '0.2.0', sessions: 1, toolCalls: 1 }, rows: [],
    });
    store.loadJudge('harnessVersion', '0.1.0', '0.2.0');
    expect(store.judge(), 'computed over the previous selection').toBeNull();
    http.expectOne(r => r.url === '/api/judge').flush(
      { status: 500, title: 'Internal error' }, { status: 500, statusText: 'Server Error' });
    expect(store.failed('judge')).toBe(true);
    expect(store.judge()).toBeNull();
  });

  it('keeps no finding of the old index open after a re-index', () => {
    store.selectFinding(7);
    http.expectOne(r => r.url === '/api/findings/7').flush(goodDetail);
    http.expectOne(r => r.url === '/api/findings/7/context').flush(goodContext);

    store.reindex();
    http.expectOne(r => r.url === '/api/index/run').flush({
      streams: 1, sessions: 1, steps: 1, toolCalls: 1, findings: 1, evidenceRows: 0, pruned: 0, parseFailures: 0, durationMs: 1,
    });
    expect(store.detail()).toBeNull();
    expect(store.context()).toBeNull();
    http.expectOne(r => r.url === '/api/overview').flush(goodOverview);
    http.expectOne(r => r.url === '/api/findings').flush(goodFindings);
  });
});
