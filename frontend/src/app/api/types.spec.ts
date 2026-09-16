import { describe, expect, it } from 'vitest';
import type { FindingDto, OverviewDto } from './types';

describe('wire format', () => {
  it('parses the overview response recorded from the running backend', () => {
    const raw = `{"tiles":{"sessions":165,"findings":389,"toolCalls":16450,"steps":13733},
      "planeMix":{"GUARD":95,"INFRASTRUCTURE":140,"MODEL_MISUSE":154},
      "topDetectors":[{"detector":"error-plane","count":233}],
      "series":[{"day":"2026-08-19","findings":46,"toolCalls":1043}],
      "throughput":[{"schema":"V0","timingSource":"none","steps":1,"medianDecodeTps":null,"medianTtftMs":null}],
      "vocabulary":{"schemas":["V0"],"models":[],"presets":[],"harnessVersions":[],"codes":[],"detectors":[]}}`;
    const parsed = JSON.parse(raw) as OverviewDto;
    expect(parsed.tiles.sessions).toBe(165);
    expect(parsed.throughput[0].medianDecodeTps).toBeNull();
    // The option lists a control can offer, and nothing else. This sample used to carry a list
    // of every session id in the index — 76% of the payload, read by no screen (types.ts says
    // why). A recorded response that grows one back is a wire change, not a typo.
    expect(Object.keys(parsed.vocabulary)).toEqual([
      'schemas', 'models', 'presets', 'harnessVersions', 'codes', 'detectors',
    ]);
  });

  it('keeps a null confidence distinguishable from a missing one', () => {
    const f = JSON.parse('{"confidence":null}') as FindingDto;
    expect(f.confidence).toBeNull();
    expect('confidence' in f).toBe(true);
  });
});
