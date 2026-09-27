export type Plane = 'GUARD' | 'MODEL_MISUSE' | 'INFRASTRUCTURE';
/**
 * What a detector concluded about one finding. The first three are stamp-guard's attribution
 * (shell-edit reuses DIRECT_MUTATION); the MISS_ values are edit-miss's reading of the
 * previous file-tool operation on the path of a failed edit.
 */
export type Category =
  | 'DIRECT_MUTATION' | 'VCS_RESTORE' | 'EXTERNAL'
  | 'REPEATED_MISS' | 'MISS_AFTER_EDIT' | 'MISS_AFTER_READ' | 'MISS_AFTER_PARTIAL_READ' | 'MISS_UNREAD';
export type TimingSource = 'chunk-events' | 'embedded-stream' | 'none';

/**
 * The option lists a control may offer — the wire shape of `inspector.dto.VocabularyOptions`.
 *
 * Every list here is bounded by the thing it enumerates, and that is why the session ids are
 * absent: they are the one list whose length is the size of the corpus (measured: 6,812 of an
 * 8,997-byte unfiltered `/api/overview` response, and growing with every session ever indexed),
 * and no control on any screen reads them. `?session=` is still a filter the server answers —
 * it just does not need a copy of every id on every dashboard load to say so.
 */
export interface Vocabulary {
  schemas: string[]; models: string[]; providers: string[]; roles: string[];
  presets: string[]; harnessVersions: string[];
  codes: string[]; detectors: string[];
}

export interface OverviewDto {
  tiles: { sessions: number; findings: number; toolCalls: number; steps: number };
  planeMix: Partial<Record<Plane, number>>;
  topDetectors: { detector: string; count: number }[];
  topCodes: { code: string; count: number }[];
  /**
   * Findings that carry no error code at all (shell-edit reports an event, not a refusal).
   * Counted apart rather than folded into `topCodes` as a null or `unknown` bar, so the codes
   * panel's rows, its "other codes" tail and this line add up to `tiles.findings`.
   */
  uncodedFindings: number;
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
  /** The harness sub-code behind `code` (a fatal turn's `unavailable_error`), or null. */
  detail: string | null;
  confidence: number | null;              // null means unattributed, not zero
  pathHint: string | null;
  seq: number | null; staleSeq: number | null; causeSeq: number | null;
  occurredAt: number; summary: string;
}

export interface EvidenceDto { seq: number; verbClass: string; pathHint: string | null; excerptRedacted: string; }
export interface FindingsPageDto { total: number; page: number; size: number; items: FindingDto[]; }
export interface FindingDetailDto { finding: FindingDto; tool: string | null; evidence: EvidenceDto[]; }

/**
 * One cohort. `guardFindings` / `violationRatePerK` are the GUARD plane under their original
 * names; misuse and infra are the other two planes, so the three plane counts sum to
 * `findings`. Every rate and delta is null when the cohort has no observed calls.
 */
export interface CohortRow {
  key: string; sessions: number; toolCalls: number; findings: number;
  guardFindings: number; misuseFindings: number; infraFindings: number;
  findingsPerKCalls: number | null; violationRatePerK: number | null;
  misuseRatePerK: number | null; infraRatePerK: number | null;
  findingsPerKCallsDelta: number | null; violationRatePerKDelta: number | null;
  misuseRatePerKDelta: number | null; infraRatePerKDelta: number | null;
}
/**
 * `baseline` is null when the index is empty (the backend's `defaultBaseline`
 * has nothing to choose from) and `basisNote` is null when the backend has no
 * basis to state — the one-row and inferred-version notes being the usual ones.
 * Both are wire facts of the response, not options of the UI.
 */
export interface CohortPageDto { groupBy: string; baseline: string | null; basisNote: string | null; cohorts: CohortRow[]; }

/**
 * The judge's answer: candidate over baseline, one row per failure (all findings, each plane,
 * each code; a codeless finding is keyed by its detector), with the 95% interval of the rate
 * ratio widened for clustering by session. Lower is better throughout — every numerator is a
 * failure — so `better` means the whole interval sits below 1 and `worse` above it.
 */
export type JudgeVerdict = 'better' | 'worse' | 'inconclusive' | 'no-data';

export interface JudgeRow {
  scope: 'total' | 'plane' | 'code';
  /** `all` for the total, a plane name for a plane row, a code (or detector) for a code row. */
  key: string;
  baselineCount: number; candidateCount: number;
  baselinePerK: number | null; candidatePerK: number | null;
  rateRatio: number | null; ratioLow: number | null; ratioHigh: number | null;
  verdict: JudgeVerdict;
  /** Sessions on each side with at least one such finding: 44 misses from one conversation and
   *  44 from forty are different evidence, and the interval knows it. */
  baselineSessions: number; candidateSessions: number;
  /** φ: how unevenly the failure falls across sessions; 1 is as evenly as chance would spread it. */
  dispersion: number;
  /**
   * The Welch–Satterthwaite degrees of freedom of the t quantile the interval used — roughly the
   * sessions that contributed. Null when there is no interval.
   */
  degreesOfFreedom: number | null;
}

export interface JudgeSide { key: string; sessions: number; toolCalls: number; }

export interface JudgeDto {
  groupBy: string; baseline: string; candidate: string; basisNote: string | null;
  baselineCohort: JudgeSide; candidateCohort: JudgeSide;
  rows: JudgeRow[];
}

/**
 * The tool calls around one finding, in seq order, and the findings among them. Structure only:
 * tool names, outcome codes, project-relative path hints and timings — no text of any kind.
 * `mark` ties a call to the finding's own seq fields; `anchorSeq` is where the window is
 * centred, which for a finding without a seq (a fatal turn) is the last call that had started.
 */
export interface ContextCall {
  seq: number; name: string | null; errorCode: string | null; plane: string | null;
  pathHint: string | null; durationMs: number | null; startedAt: number | null;
  mark: 'finding' | 'stale' | 'cause' | null;
}
export interface ContextNeighbour {
  id: number; detector: string; code: string | null; category: string | null; seq: number | null;
}
export interface FindingContextDto {
  findingId: number; anchorSeq: number | null; calls: ContextCall[]; findings: ContextNeighbour[];
}

/** One kind of finding in the selection, busiest first: the detector and what it concluded. */
export interface BreakdownRow {
  detector: string; plane: Plane; category: Category | null; code: string | null; detail: string | null;
  count: number; perKCalls: number | null;
}

/**
 * `pruned` is how many streams the run discarded because the corpus no longer holds them.
 * It is a count and nothing else — the run reports what it removed, never which file.
 * `evidenceRows` is how many redacted evidence rows the run stored, a subset of `findings`;
 * the count is the only thing about evidence that crosses the wire (§4.1).
 */
export interface IndexSummaryDto {
  streams: number; sessions: number; steps: number; toolCalls: number;
  findings: number; evidenceRows: number; pruned: number; parseFailures: number; durationMs: number;
}

export type SortField = 'code' | 'detector' | 'session' | 'plane' | 'time' | 'confidence';
export type SortDir = 'asc' | 'desc';
export const SORT_FIELDS: readonly SortField[] = ['code', 'detector', 'session', 'plane', 'time', 'confidence'];
