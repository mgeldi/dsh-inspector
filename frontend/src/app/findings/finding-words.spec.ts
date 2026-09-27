import { describe, expect, it } from 'vitest';
import type { Category, FindingDto } from '../api/types';
import { categoryChip, chainSteps, confidenceLabel, confidenceTip } from './finding-words';

// Invented fixture data only — paths, seqs and sessions are not from any real corpus.
function finding(over: Partial<FindingDto>): FindingDto {
  return {
    id: 1, sessionId: 'demo-session-1', detector: 'stamp-guard', plane: 'GUARD',
    category: null, code: null, detail: null, confidence: null,
    pathHint: 'src/demo/widget.ts', seq: null, staleSeq: null, causeSeq: null,
    occurredAt: Date.parse('2026-09-20T10:00:00Z'), summary: 'demo',
    ...over,
  };
}

const labels = (f: FindingDto) => chainSteps(f).map(s => s.label);

/**
 * The three seq fields are shared by every detector and mean something different in each.
 * stamp-guard's stamped → changed → refused is one reading; painted over an edit miss it would
 * call a read a stamp, and over a shell rewrite it would name a refusal that never happened.
 */
describe('chainSteps', () => {
  it('keeps the stamp-guard wording for stamp-guard', () => {
    const f = finding({ detector: 'stamp-guard', staleSeq: 145, causeSeq: 150, seq: 300 });
    expect(labels(f)).toEqual(['stamped at 145', 'changed at 150', 'refused at 300']);
    expect(chainSteps(f).map(s => s.final)).toEqual([false, false, true]);
  });

  it('reads an edit miss as the previous operation on the path, then the miss', () => {
    const miss = (category: Category, causeSeq: number | null) => labels(finding({
      detector: 'edit-miss', plane: 'MODEL_MISUSE', category, code: 'FS_EDIT_NOT_FOUND',
      confidence: 0.9, causeSeq, seq: 420,
    }));

    expect(miss('REPEATED_MISS', 410)).toEqual(['missed before at 410', 'edit missed at 420']);
    expect(miss('MISS_AFTER_EDIT', 410)).toEqual(['own edit or write at 410', 'edit missed at 420']);
    expect(miss('MISS_AFTER_READ', 410)).toEqual(['read at 410', 'edit missed at 420']);
    expect(miss('MISS_AFTER_PARTIAL_READ', 410)).toEqual(['partial read at 410', 'edit missed at 420']);
    // nothing touched the file before: no invented predecessor, just the miss
    expect(miss('MISS_UNREAD', null)).toEqual(['edit missed at 420']);
  });

  it('reads a shell edit as the file-tool touch, then the rewrite from the shell', () => {
    const f = finding({
      detector: 'shell-edit', plane: 'MODEL_MISUSE', category: 'DIRECT_MUTATION',
      confidence: 0.9, staleSeq: 200, seq: 230,
    });
    expect(labels(f)).toEqual(['tracked by a file tool at 200', 'rewritten from the shell at 230']);
  });

  it('never lends stamp-guard words to a detector that did not stamp anything', () => {
    for (const detector of ['error-plane', 'retry-storm']) {
      const text = labels(finding({ detector, plane: 'INFRASTRUCTURE', seq: 77 })).join(' ');
      expect(text, detector).toBe('reported at 77');
      expect(text).not.toMatch(/stamped|refused/);
    }
    // a fatal turn carries no seq at all, so it has no chain to draw
    expect(chainSteps(finding({ detector: 'fatal-turn', plane: 'INFRASTRUCTURE' }))).toEqual([]);
  });
});

describe('category and confidence', () => {
  it('names every category in words, and only a direct mutation raises the alarm hue', () => {
    const all: Category[] = [
      'DIRECT_MUTATION', 'VCS_RESTORE', 'EXTERNAL',
      'REPEATED_MISS', 'MISS_AFTER_EDIT', 'MISS_AFTER_READ', 'MISS_AFTER_PARTIAL_READ', 'MISS_UNREAD',
    ];
    expect(all.map(c => categoryChip(c).label)).toEqual([
      'direct mutation', 'vcs restore · legitimate', 'external',
      'repeated miss', 'miss after own edit', 'miss after read', 'miss, partial read', 'miss, file unread',
    ]);
    expect(all.filter(c => categoryChip(c).cls === 'cat-mutation')).toEqual(['DIRECT_MUTATION']);
  });

  /**
   * edit-miss's HIGH is not a path match: the category is a fact of the order of calls. The
   * stamp-guard sentence ("absolute-path match plus a mutating verb") would be a false account
   * of how the row was scored.
   */
  it('explains an edit-miss high as the order of calls, not a path match', () => {
    expect(confidenceLabel(0.9, 'REPEATED_MISS')).toBe('high');
    expect(confidenceTip(0.9, 'REPEATED_MISS')).toMatch(/order of file-tool calls/);
    expect(confidenceTip(0.9, 'REPEATED_MISS')).not.toMatch(/path match/);

    // shell-edit scores by path match, the same evidence scale as stamp-guard
    expect(confidenceTip(0.9, 'DIRECT_MUTATION')).toMatch(/absolute-path match/);
    expect(confidenceTip(0.6, 'DIRECT_MUTATION')).toMatch(/basename match/);
  });

  it('still tells a failed search from a search that never ran', () => {
    expect(confidenceLabel(null, 'EXTERNAL')).toBe('unattributed');
    expect(confidenceLabel(null, null)).toBe('n/a');
    expect(confidenceTip(null, null)).toMatch(/does not attribute a cause/);
  });
});
