import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FindingDto, FindingDetailDto, FindingsPageDto, OverviewDto } from '../api/types';
import { InsightsStore } from './insights.store';

// Invented fixture data: the model name, session id and paths are not from any real corpus.
const goodOverview: OverviewDto = {
  tiles: { sessions: 165, findings: 389, toolCalls: 16450, steps: 13733 },
  planeMix: { GUARD: 95, INFRASTRUCTURE: 140, MODEL_MISUSE: 154 },
  topDetectors: [{ detector: 'error-plane', count: 233 }, { detector: 'retry-storm', count: 72 }],
  series: [{ day: '2026-09-01', findings: 46, toolCalls: 1043 }],
  throughput: [
    { schema: 'V0', timingSource: 'chunk-events', steps: 9040, medianDecodeTps: 163.4, medianTtftMs: 563.5 },
  ],
  vocabulary: {
    schemas: ['V0', 'V3'], models: ['demo-flash-8b'], presets: ['smoke'],
    harnessVersions: ['0.1.0'], codes: ['FS_STALE_VERSION'],
    detectors: ['error-plane', 'retry-storm'], sessions: ['demo-session-1'],
  },
};

const finding7: FindingDto = {
  id: 7, sessionId: 'demo-session-1', detector: 'error-plane', plane: 'GUARD',
  category: 'DIRECT_MUTATION', code: 'FS_STALE_VERSION', confidence: 0.9,
  pathHint: 'src/demo/app.ts', seq: 300, staleSeq: 145, causeSeq: 150,
  occurredAt: 1_790_000_000_000,
  summary: 'Demo finding: the write was refused because the file was stamped before it was read.',
};

const goodFindings: FindingsPageDto = { total: 389, page: 0, size: 20, items: [finding7] };

const goodDetail: FindingDetailDto = { finding: finding7, tool: 'demo-tool', evidence: [] };

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
    store.setPreset('7d', 1_790_000_000_000);
    store.setSchema('V0');
    store.loadOverview();
    store.loadFindings();

    const overviewReq = http.expectOne(r => r.url === '/api/overview');
    const findingsReq = http.expectOne(r => r.url === '/api/findings');

    for (const key of ['from', 'to', 'schema', 'model', 'preset', 'harnessVersion']) {
      expect(findingsReq.request.params.get(key), key).toBe(overviewReq.request.params.get(key));
    }
    expect(overviewReq.request.params.get('to')).toBe('1790000000000');
    expect(overviewReq.request.params.get('from')).toBe('1789395200000');
    expect(overviewReq.request.params.get('schema')).toBe('V0');

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
    store.setSchema('V0');
    store.setSchema(null);
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

    store.setSchema('nope');
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

    store.setDetector('nope');
    store.loadFindings();
    http.expectOne(r => r.url === '/api/findings').flush(
      { status: 400, title: 'Unknown filter value', filter: 'detector', value: 'nope', allowed: ['error-plane', 'retry-storm'] },
      { status: 400, statusText: 'Bad Request' });

    expect(store.findings()?.total).toBe(389);
    expect(store.findings()?.items[0].id).toBe(7);
    expect(store.error()).toContain('error-plane');
    expect(store.error()).toContain('retry-storm');
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

  it('fetches the detail lazily and caches it per id: two opens of the same id issue one request', () => {
    expect(store.detail()).toBeNull();

    store.selectFinding(7);
    expect(store.detail()).toBeNull(); // lazy: nothing is set before the response
    const req = http.expectOne(r => r.url === '/api/findings/7');
    req.flush(goodDetail);
    expect(store.detail()?.finding.id).toBe(7);

    store.selectFinding(7);
    expect(store.detail()?.finding.id).toBe(7);
    expect(http.match(() => true)).toHaveLength(0); // the second open hit the cache
  });

  it('invalidates the detail cache on any reload, so a re-indexed corpus is not served stale', () => {
    store.selectFinding(7);
    http.expectOne(r => r.url === '/api/findings/7').flush(goodDetail);
    store.selectFinding(7);
    expect(http.match(() => true)).toHaveLength(0); // cached

    store.loadFindings();
    http.expectOne(r => r.url === '/api/findings').flush(goodFindings);

    store.selectFinding(7);
    const req = http.expectOne(r => r.url === '/api/findings/7'); // cache gone, refetched
    req.flush(goodDetail);
  });

  it('reindex posts, reloads overview and findings, and exposes the returned counts for a status line', () => {
    store.reindex();
    const post = http.expectOne(r => r.url === '/api/index/run');
    expect(post.request.method).toBe('POST');
    post.flush({
      streams: 168, sessions: 165, steps: 13733, toolCalls: 16450, findings: 389, parseFailures: 0, durationMs: 4300,
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
});
