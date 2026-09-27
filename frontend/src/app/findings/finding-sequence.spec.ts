import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import type { FindingDto } from '../api/types';
import { FindingSequence } from './finding-sequence';

// Invented values: no real session, path or project.
const finding: FindingDto = {
  id: 3, sessionId: 'demo-session', detector: 'edit-miss', plane: 'MODEL_MISUSE',
  category: 'MISS_AFTER_PARTIAL_READ', code: 'FS_EDIT_NOT_FOUND', detail: null, confidence: 0.9,
  pathHint: 'src/demo.ts', seq: 20, staleSeq: null, causeSeq: 18, occurredAt: 1_790_000_000_000,
  summary: 'demo.ts: edit found no match; the read at seq 18 covered only part of the file',
};

describe('FindingSequence', () => {
  const render = (failed: boolean) => {
    const fixture = TestBed.createComponent(FindingSequence);
    fixture.componentRef.setInput('finding', finding);
    fixture.componentRef.setInput('context', null);
    fixture.componentRef.setInput('failed', failed);
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  };

  it('waits while the calls are on their way', () => {
    expect(render(false)).toContain('Loading the calls around it');
  });

  /** A request that came back as an error is an answer; "Loading…" after it is a wait for nothing. */
  it('says the calls could not be loaded once the request has failed', () => {
    const text = render(true);
    expect(text).toContain('could not be loaded');
    expect(text).not.toContain('Loading');
  });
});
