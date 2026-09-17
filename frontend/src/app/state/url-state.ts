import { applyPreset, emptyFilters, PRESETS, type Filters, type PresetId } from './filters';
import type { SortDir, SortField } from '../api/types';

/**
 * The query string as the single description of what is on screen.
 *
 * <p>Everything a user can set — the rail's facets, the time range, the code drill-down, the
 * sort, the page and its size, the cohort axis — lives here rather than only in a signal.
 * Before this, a filtered view could not be linked to, a reload lost it, and the back button
 * left the screen showing one thing while the URL claimed another. The store still owns every
 * fetch; it is fed from the URL instead of from the controls, so there is one direction of
 * travel: a control navigates, the URL changes, the store reads it, the screen follows.
 *
 * <p>Two rules keep the URL readable. A value equal to the default is absent rather than
 * spelled out, so the common case is a bare path and a shared link carries only what was
 * actually chosen. And `page` is one-based here because that is the number the pager shows —
 * the zero-based index is an implementation detail of the API and stays behind this boundary.
 */

/** The time range travels as its preset id, not as two epoch stamps. */
export type UrlRange = PresetId;

export interface UrlState {
  filters: Filters;
  code: string | null;
  sort: { field: SortField; dir: SortDir } | null;
  /** Zero-based, as the store and the API want it. */
  page: number;
  size: number;
  groupBy: string | null;
  baseline: string | null;
}

export const DEFAULT_SIZE = 20;
export const DEFAULT_SORT = 'time:desc';

const FACETS = ['schema', 'model', 'preset', 'harnessVersion'] as const;
const SORT_FIELDS: readonly SortField[] = ['time', 'plane', 'detector', 'code', 'session', 'confidence'];
const PRESET_IDS: readonly PresetId[] = PRESETS.map(p => p.id);

/** A plain record of the parameters, ready for `router.navigate`. */
export type UrlParams = Record<string, string | null>;

/**
 * Read the state a URL describes. Unknown or malformed values fall back to the default
 * rather than throwing: a hand-edited or truncated link should open the dashboard, not an
 * error, and the server rejects a facet value it does not know anyway — with a sentence
 * naming the allowed set, which is a better answer than anything this function could give.
 */
export function fromParams(get: (key: string) => string | null, now: number = Date.now()): UrlState {
  const range = get('range');
  const presetId: PresetId = PRESET_IDS.includes(range as PresetId) ? range as PresetId : 'all';
  let filters: Filters = applyPreset(emptyFilters(), presetId, now);

  for (const facet of FACETS) {
    const value = get(facet);
    if (value !== null && value !== '') {
      filters = { ...filters, [facet]: value };
    }
  }

  return {
    filters,
    code: emptyToNull(get('code')),
    sort: parseSort(get('sort')),
    page: parsePage(get('page')),
    size: parseSize(get('size')),
    groupBy: emptyToNull(get('groupBy')),
    baseline: emptyToNull(get('baseline')),
  };
}

/**
 * The parameters a state should be written as. A null value removes the key, which is what
 * `queryParamsHandling: 'merge'` needs to hear in order to drop one — leaving it out entirely
 * would keep whatever was there, and a filter that cannot be cleared from the URL is worse
 * than one that was never in it.
 */
export function toParams(state: Partial<UrlState>): UrlParams {
  const params: UrlParams = {};

  if (state.filters !== undefined) {
    params['range'] = state.filters.presetId === 'all' ? null : state.filters.presetId;
    for (const facet of FACETS) {
      params[facet] = state.filters[facet] ?? null;
    }
  }
  if (state.code !== undefined) {
    params['code'] = state.code;
  }
  if (state.sort !== undefined) {
    const spelled = state.sort === null ? null : `${state.sort.field}:${state.sort.dir}`;
    params['sort'] = spelled === DEFAULT_SORT ? null : spelled;
  }
  if (state.page !== undefined) {
    params['page'] = state.page === 0 ? null : String(state.page + 1);
  }
  if (state.size !== undefined) {
    params['size'] = state.size === DEFAULT_SIZE ? null : String(state.size);
  }
  if (state.groupBy !== undefined) {
    params['groupBy'] = state.groupBy;
  }
  if (state.baseline !== undefined) {
    params['baseline'] = state.baseline;
  }
  return params;
}

/**
 * Whether two states differ in a way the shared screens have to re-ask about. The findings
 * table also re-asks on its own axis (code, sort, page, size); this is the narrower question
 * of whether the overview and the cohort rates describe a different population now, so a page
 * change does not cost three requests for screens nobody moved.
 */
export function sharedFiltersDiffer(a: UrlState, b: UrlState): boolean {
  if (a.filters.presetId !== b.filters.presetId) { return true; }
  return FACETS.some(facet => (a.filters[facet] ?? null) !== (b.filters[facet] ?? null));
}

export function findingsAxisDiffers(a: UrlState, b: UrlState): boolean {
  return a.code !== b.code
    || a.page !== b.page
    || a.size !== b.size
    || spellSort(a.sort) !== spellSort(b.sort);
}

function spellSort(sort: UrlState['sort']): string {
  return sort === null ? DEFAULT_SORT : `${sort.field}:${sort.dir}`;
}

function emptyToNull(value: string | null): string | null {
  return value === null || value === '' ? null : value;
}

/** `key:dir`, both halves case-insensitive, and anything else is the default. */
function parseSort(raw: string | null): { field: SortField; dir: SortDir } | null {
  if (raw === null || raw === '') { return null; }
  const [rawField, rawDir = 'desc'] = raw.split(':');
  const field = rawField.toLowerCase() as SortField;
  const dir = rawDir.toLowerCase() as SortDir;
  if (!SORT_FIELDS.includes(field) || (dir !== 'asc' && dir !== 'desc')) { return null; }
  return { field, dir };
}

/** One-based on the wire, zero-based in the store. Anything below page 1 is page 1. */
function parsePage(raw: string | null): number {
  const n = Number(raw);
  return Number.isInteger(n) && n > 1 ? n - 1 : 0;
}

function parseSize(raw: string | null): number {
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 && n <= 200 ? n : DEFAULT_SIZE;
}
