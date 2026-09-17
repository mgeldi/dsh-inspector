import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SIZE, findingsAxisDiffers, fromParams, sharedFiltersDiffer, toParams, type UrlState,
} from './url-state';

/** A `get` over a plain object, which is the shape ParamMap presents. */
function params(record: Record<string, string>): (key: string) => string | null {
  return key => (key in record ? record[key] : null);
}

const NOW = Date.parse('2026-09-17T12:00:00Z');

describe('url-state', () => {
  it('reads an empty query as the default view', () => {
    const state = fromParams(params({}), NOW);

    expect(state.filters.presetId).toBe('all');
    expect(state.filters.from).toBeNull();
    expect(state.filters.schema).toBeUndefined();
    expect(state.code).toBeNull();
    expect(state.sort).toBeNull();
    expect(state.page).toBe(0);
    expect(state.size).toBe(DEFAULT_SIZE);
  });

  /**
   * The default view has to produce a bare URL. If every default were spelled out, the common
   * case would be a wall of parameters and a shared link would say nothing about what was
   * actually chosen.
   */
  it('writes nothing for a default state, and null for a value being cleared', () => {
    const state = fromParams(params({}), NOW);
    const written = toParams({ ...state, code: null });

    expect(written['range']).toBeNull();
    expect(written['page']).toBeNull();
    expect(written['size']).toBeNull();
    expect(written['sort']).toBeNull();
    expect(written['code']).toBeNull();
    expect(written['schema']).toBeNull();
  });

  it('carries the range as its preset id and derives the window from it', () => {
    const state = fromParams(params({ range: '24h' }), NOW);

    expect(state.filters.presetId).toBe('24h');
    expect(state.filters.to).toBe(NOW);
    expect(state.filters.from).toBe(NOW - 86_400_000);
    expect(toParams(state)['range']).toBe('24h');
  });

  /**
   * The pager counts from one and the API counts from zero. The URL shows the number the user
   * sees; the conversion lives here so neither side has to know about the other's counting.
   */
  it('translates between the one-based page in the URL and the zero-based page in the store', () => {
    expect(fromParams(params({ page: '3' }), NOW).page).toBe(2);
    expect(toParams({ page: 2 })['page']).toBe('3');

    // page one is the default and therefore absent
    expect(toParams({ page: 0 })['page']).toBeNull();
    expect(fromParams(params({ page: '1' }), NOW).page).toBe(0);
  });

  it('round-trips a fully specified view', () => {
    const written = {
      range: '7d', schema: 'V3', model: 'demo-model', preset: 'builder',
      harnessVersion: 'v2', code: 'FS_STALE_VERSION', sort: 'confidence:asc',
      page: '4', size: '50', groupBy: 'schema', baseline: 'V0',
    };
    const state = fromParams(params(written), NOW);

    expect(state.filters.schema).toBe('V3');
    expect(state.filters.harnessVersion).toBe('v2');
    expect(state.code).toBe('FS_STALE_VERSION');
    expect(state.sort).toEqual({ field: 'confidence', dir: 'asc' });
    expect(state.page).toBe(3);
    expect(state.size).toBe(50);
    expect(state.groupBy).toBe('schema');

    expect(toParams(state)).toMatchObject({
      range: '7d', schema: 'V3', code: 'FS_STALE_VERSION',
      sort: 'confidence:asc', page: '4', size: '50', groupBy: 'schema', baseline: 'V0',
    });
  });

  /**
   * A link is typed, truncated and edited by hand. None of that should produce an error
   * screen: the server already rejects a facet value it does not know, with a sentence naming
   * the allowed set, which is a better answer than anything this parser could invent.
   */
  it('falls back to the default for a value it cannot read', () => {
    const state = fromParams(params({
      range: 'yesterday', sort: 'nonsense:sideways', page: '-4', size: '99999',
    }), NOW);

    expect(state.filters.presetId).toBe('all');
    expect(state.sort).toBeNull();
    expect(state.page).toBe(0);
    expect(state.size).toBe(DEFAULT_SIZE);
  });

  it('accepts a sort in any casing, matching what the API accepts', () => {
    expect(fromParams(params({ sort: 'TIME:DESC' }), NOW).sort).toEqual({ field: 'time', dir: 'desc' });
  });

  /**
   * A page change must not re-ask the overview and the cohort rates: they describe the same
   * population as before, and three requests for two screens nobody moved is the fan-out this
   * split exists to avoid.
   */
  it('separates a change of population from a change of page', () => {
    const base: UrlState = fromParams(params({}), NOW);
    const nextPage: UrlState = fromParams(params({ page: '2' }), NOW);
    const narrowed: UrlState = fromParams(params({ schema: 'V3' }), NOW);

    expect(sharedFiltersDiffer(base, nextPage)).toBe(false);
    expect(findingsAxisDiffers(base, nextPage)).toBe(true);

    expect(sharedFiltersDiffer(base, narrowed)).toBe(true);
    expect(findingsAxisDiffers(base, narrowed)).toBe(false);
  });
});
