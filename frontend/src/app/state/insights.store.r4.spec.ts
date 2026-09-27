import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { InsightsStore } from './insights.store';

/**
 * Each lane keeps its own failure reason. The error bar is one sentence, and on a fresh link the
 * overview's 200 lands milliseconds after the cohorts' 400 and clears it; a screen pointing at
 * the bar then pointed at nothing.
 */
describe('InsightsStore, round 4', () => {
  let store: InsightsStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('keeps a lane\'s reason, with its status, after a later success clears the bar', () => {
    store.loadCohorts('model', 'inv-gone');
    store.loadBreakdown();
    http.expectOne(r => r.url === '/api/cohorts').flush(
      { status: 400, title: 'Unknown filter value', filter: 'baseline', value: 'inv-gone', allowed: ['inv-a'] },
      { status: 400, statusText: 'Bad Request' });
    http.expectOne(r => r.url === '/api/breakdown').flush([]);

    expect(store.error(), 'the bar was cleared by the later success').toBeNull();
    expect(store.failure('cohorts')).toBe("400 · Unknown filter value: 'inv-gone' is not a valid baseline. Valid: inv-a");
    expect(store.failure('breakdown')).toBeNull();

    store.loadCohorts('model');
    expect(store.failure('cohorts'), 'a new request is not failed yet').toBeNull();
    http.expectOne(r => r.url === '/api/cohorts').flush({ groupBy: 'model', baseline: 'inv-a', basisNote: null, cohorts: [] });
  });
});
