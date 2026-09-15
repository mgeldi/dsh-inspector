// The shared filter values, mirroring the backend's InsightFilter (from/to epoch millis,
// plus schema, model, preset, harnessVersion). Task 3 needed only this shape; the preset
// logic and its spec are Task 4's.
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
  preset?: string | null;
  harnessVersion?: string | null;
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
