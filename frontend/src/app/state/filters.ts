// The shared filter values, mirroring the backend's InsightFilter: from/to epoch millis, plus
// one string per facet in FACET_KEYS.
//
// The string facets are `string | null` (still optional): the rail's "Clear" writes null,
// and absence — not an empty string — is what means "not filtered", because the backend
// reads '' as a value and answers 400. api.service.spec.ts passes a partial object as
// filters, which is why the fields must stay optional.
export interface FilterValues {
  from?: number | null;
  to?: number | null;
  schema?: string | null;
  model?: string | null;
  provider?: string | null;
  role?: string | null;
  preset?: string | null;
  harnessVersion?: string | null;
}

/**
 * The string facets of the filter contract, in the order the rail shows them — and the one
 * list of them. The request params, the URL round-trip, the rail, the "is anything filtered"
 * checks and the active-filter count all iterate this, so a facet the backend adds is one
 * entry here rather than five hand-kept copies of the same four words, one of which is
 * forgotten.
 */
export const FACET_KEYS = ['schema', 'model', 'provider', 'role', 'preset', 'harnessVersion'] as const;
export type FacetKey = (typeof FACET_KEYS)[number];

/**
 * Whether a facet narrows the selection. `!= null` on purpose: an untouched facet is absent
 * (undefined) and a cleared one is null, and both mean "not filtered".
 */
export function facetIsSet(f: FilterValues, key: FacetKey): boolean {
  const value = f[key];
  return value != null && value !== '';
}

/**
 * The filters with one facet taken out — the cohort axis, for the cohorts and judge requests.
 * Grouping by a facet asks for every value of it side by side, and a filter on that facet
 * would leave one row whose delta against itself is zero. The filter is not dropped from the
 * URL or the rail: it is the reader's choice for the other screens, and the rail says it is
 * not applied here.
 */
export function withoutFacet<F extends FilterValues>(f: F, key: FacetKey): F {
  return { ...f, [key]: null };
}

export type PresetId = '24h' | '7d' | '30d' | 'all';

export const PRESETS: readonly { id: PresetId; label: string; ms: number | null }[] = [
  { id: '24h', label: '24 h', ms: 86_400_000 },
  { id: '7d', label: '7 d', ms: 7 * 86_400_000 },
  { id: '30d', label: '30 d', ms: 30 * 86_400_000 },
  { id: 'all', label: 'All time', ms: null },
];

/** 'all' by design: a reviewer on a machine whose clock is past the fixture dates must not
 *  see an empty dashboard and conclude the tool found nothing. */
export const DEFAULT_PRESET: PresetId = 'all';

export type Filters = FilterValues & { presetId: PresetId };

export function emptyFilters(): Filters {
  return { presetId: DEFAULT_PRESET };
}

/**
 * Presets only — no datepicker. `from` and `to` are both null for 'all': pinning `to` to
 * the request time would make the response depend on the moment it was asked, and
 * absence, not an empty value, is what keeps the range open.
 */
export function applyPreset(f: Filters, id: PresetId, now: number = Date.now()): Filters {
  const preset = PRESETS.find(p => p.id === id)!;
  return {
    ...f,
    presetId: id,
    from: preset.ms === null ? null : now - preset.ms,
    to: preset.ms === null ? null : now,
  };
}
