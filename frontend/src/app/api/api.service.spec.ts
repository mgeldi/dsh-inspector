import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ApiService } from './api.service';

describe('ApiService', () => {
  let api: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('asks /api/findings with the sort serialised as field:dir', () => {
    api.findings({ sort: { field: 'confidence', dir: 'asc' }, page: 2, size: 20 }).subscribe();
    const req = http.expectOne(r => r.url === '/api/findings');
    expect(req.request.params.get('sort')).toBe('confidence:asc');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('from')).toBeNull();   // unset filters are absent, not empty
    req.flush({ total: 0, page: 2, size: 20, items: [] });
  });

  it('never sends an empty filter value, which the backend would reject', () => {
    api.findings({ filters: { schema: '', model: 'demo-flash-8b' }, page: 0, size: 20 }).subscribe();
    const req = http.expectOne(r => r.url === '/api/findings');
    expect(req.request.params.has('schema')).toBe(false);
    expect(req.request.params.get('model')).toBe('demo-flash-8b');
    req.flush({ total: 0, page: 0, size: 20, items: [] });
  });

  it('posts the re-index and reports the counts', () => {
    api.runIndex().subscribe();
    http.expectOne('/api/index/run').flush({
      streams: 12, sessions: 11, steps: 22, toolCalls: 32, findings: 9, evidenceRows: 3,
      pruned: 0, parseFailures: 0, durationMs: 40
    });
  });
});
