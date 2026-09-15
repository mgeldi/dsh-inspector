export type Plane = 'GUARD' | 'MODEL_MISUSE' | 'INFRASTRUCTURE';
export type Category = 'DIRECT_MUTATION' | 'VCS_RESTORE' | 'EXTERNAL';
export type TimingSource = 'chunk-events' | 'embedded-stream' | 'none';

export interface Vocabulary {
  schemas: string[]; models: string[]; presets: string[]; harnessVersions: string[];
  codes: string[]; detectors: string[]; sessions: string[];
}

export interface OverviewDto {
  tiles: { sessions: number; findings: number; toolCalls: number; steps: number };
  planeMix: Partial<Record<Plane, number>>;
  topDetectors: { detector: string; count: number }[];
  series: { day: string; findings: number; toolCalls: number }[];
  throughput: {
    schema: string; timingSource: TimingSource; steps: number;
    medianDecodeTps: number | null; medianTtftMs: number | null;
  }[];
  vocabulary: Vocabulary;
}

export interface FindingDto {
  id: number; sessionId: string; detector: string; plane: Plane;
  category: Category | null; code: string | null;
  confidence: number | null;              // null means unattributed, not zero
  pathHint: string | null;
  seq: number | null; staleSeq: number | null; causeSeq: number | null;
  occurredAt: number; summary: string;
}

export interface EvidenceDto { seq: number; verbClass: string; pathHint: string | null; excerptRedacted: string; }
export interface FindingsPageDto { total: number; page: number; size: number; items: FindingDto[]; }
export interface FindingDetailDto { finding: FindingDto; tool: string | null; evidence: EvidenceDto[]; }

export interface CohortRow {
  key: string; sessions: number; toolCalls: number; findings: number; guardFindings: number;
  findingsPerKCalls: number | null; violationRatePerK: number | null;
  findingsPerKCallsDelta: number | null; violationRatePerKDelta: number | null;
}
/**
 * `baseline` is null when the index is empty (the backend's `defaultBaseline`
 * has nothing to choose from) and `basisNote` is null when the backend has no
 * basis to state — the one-row and inferred-version notes being the usual ones.
 * Both are wire facts of the response, not options of the UI.
 */
export interface CohortPageDto { groupBy: string; baseline: string | null; basisNote: string | null; cohorts: CohortRow[]; }

export interface IndexSummaryDto {
  streams: number; sessions: number; steps: number; toolCalls: number;
  findings: number; parseFailures: number; durationMs: number;
}

export type SortField = 'code' | 'detector' | 'session' | 'plane' | 'time' | 'confidence';
export type SortDir = 'asc' | 'desc';
export const SORT_FIELDS: readonly SortField[] = ['code', 'detector', 'session', 'plane', 'time', 'confidence'];
