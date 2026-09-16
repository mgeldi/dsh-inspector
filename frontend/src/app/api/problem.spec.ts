import { describe, expect, it } from 'vitest';
import { describeProblem, type ProblemDetail } from './problem';

/** The shape `/api/*` actually returns for a rejected filter value. */
const rejected = (filter: string, allowed: string[]): ProblemDetail => ({
  type: 'urn:dsh-inspector:unknown-filter-value',
  title: 'Unknown filter value',
  status: 400,
  detail: `filter '${filter}' value 'nope' is not in the index vocabulary`,
  filter,
  value: 'nope',
  allowed,
});

const ids = (n: number) =>
  Array.from({ length: n }, (_, i) => `id-${String(i).padStart(3, '0')}`);

describe('the sentence in the error bar', () => {
  it('names a short vocabulary in full, because that is the case the rail repairs itself from', () => {
    expect(describeProblem(rejected('schema', ['V0', 'V3']))).toBe(
      "Unknown filter value: 'nope' is not a valid schema. Valid: V0, V3",
    );
  });

  it('does not truncate the six sort keys — the cap is set above the largest list a screen can send', () => {
    const sentence = describeProblem(
      rejected('sort', ['time', 'plane', 'detector', 'code', 'session', 'confidence']),
    );
    expect(sentence).toContain('confidence');
    expect(sentence).not.toContain('more');
  });

  it('stays a sentence when the vocabulary is 165 session ids, which is what the API can send', () => {
    const sentence = describeProblem(rejected('session', ids(165)));
    expect(sentence).toContain('id-005');
    expect(sentence).not.toContain('id-006');
    expect(sentence).toContain('and 159 more');
    expect(sentence.length).toBeLessThan(200);
  });

  it('says nothing is left out when the list is exactly the cap', () => {
    expect(describeProblem(rejected('code', ids(6)))).not.toContain('more');
  });

  it('falls back to the server sentence when the problem carries no list', () => {
    expect(
      describeProblem({ status: 400, title: 'Bad request', detail: 'size must be <= 200, was 201' }),
    ).toBe('size must be <= 200, was 201');
  });
});
