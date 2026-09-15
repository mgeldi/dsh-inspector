import { describe, expect, it } from 'vitest';
import { DEFAULT_PRESET, PRESETS, applyPreset, emptyFilters } from './filters';

describe('filters', () => {
  it('defaults to the whole time range, because fixtures are dated and a clock can drift', () => {
    expect(DEFAULT_PRESET).toBe('all');
    expect(applyPreset(emptyFilters(), 'all', 1_790_000_000_000).from).toBeNull();
  });

  it('computes a 7-day window backwards from the supplied clock', () => {
    const now = 1_790_000_000_000;
    const f = applyPreset(emptyFilters(), '7d', now);
    expect(f.to).toBe(now);
    expect(f.from).toBe(now - 7 * 86_400_000);
    expect(f.presetId).toBe('7d');
  });

  it('offers exactly the four presets the design allows, and no datepicker', () => {
    expect(PRESETS.map(p => p.id)).toEqual(['24h', '7d', '30d', 'all']);
  });

  it('carries nothing but the preset when no facet is chosen', () => {
    // Absence, not empty string: the backend reads '' as a value and answers 400.
    expect(Object.keys(emptyFilters())).toEqual(['presetId']);
  });
});
