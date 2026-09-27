import { ChangeDetectionStrategy, Component, computed, effect, inject, input, untracked } from '@angular/core';
import type { CohortPageDto, JudgeRow, JudgeVerdict, Plane } from '../api/types';
import type { FacetKey } from '../state/filters';
import { InsightsStore } from '../state/insights.store';
import { ViewUrl } from '../state/view-url';

const PLANE_LABELS: Record<Plane, string> = {
  GUARD: 'Guard', MODEL_MISUSE: 'Model misuse', INFRASTRUCTURE: 'Infrastructure',
};

const VERDICT_LABELS: Record<JudgeVerdict, string> = {
  better: 'better', worse: 'worse', inconclusive: 'inconclusive', 'no-data': 'no data',
};

/** One judge row as the table prints it. */
interface JudgeLine {
  row: JudgeRow;
  label: string;
  /** A code row: the key is an identifier, set in monospace. */
  code: boolean;
  /** The first code row, where the table turns from the totals to the codes. */
  firstCode: boolean;
  ratio: string;
  /** One side has none of this failure, so the backend's ratio is Haldane-corrected. */
  corrected: boolean;
}

/**
 * The judge under the cohort table: whether the candidate cohort is better or worse than the
 * baseline beyond chance, per failure. The deltas above say how far apart two rates are; they
 * cannot say whether 12 → 44 over six sessions is a regression or one bad conversation, and
 * that is the question a harness change has to answer before it ships.
 *
 * <p>It judges the table it sits under, not the URL: the axis and the baseline come from the
 * cohort page on screen, so the verdict and the deltas beside it always describe the same pair
 * in the same selection, including in the moment between a new request and its answer.
 */
@Component({
  selector: 'app-cohort-judge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './judge.html',
  styleUrl: './judge.scss',
})
export class CohortJudge {
  readonly page = input.required<CohortPageDto>();

  readonly store = inject(InsightsStore);
  private readonly url = inject(ViewUrl);

  readonly baseline = computed(() => this.page().baseline ?? '');

  /** Every cohort in the table but the baseline: a cohort judged against itself says nothing. */
  readonly candidateKeys = computed<string[]>(() =>
    this.page().cohorts.map(c => c.key).filter(k => k !== this.baseline()));

  /**
   * The cohort the judge answers for: the URL's choice while this table still has it. With no
   * choice in the URL, the only other cohort when there is exactly one — the backend's own
   * default, made explicit so the request never depends on how many cohorts a selection holds.
   *
   * <p>A choice the table no longer offers is never replaced silently. With two cohorts, a
   * candidate that became the re-picked baseline used to fall back to "the only other one" —
   * the old baseline — so the judge compared the reverse pair: the ratio inverted, better and
   * worse swapped, and nothing on screen said so. It asks instead, with `candidateNote` saying why.
   */
  readonly candidate = computed<string | null>(() => {
    const keys = this.candidateKeys();
    const chosen = this.store.judgeCandidate();
    if (chosen !== null) { return keys.includes(chosen) ? chosen : null; }
    return keys.length === 1 ? keys[0] : null;
  });

  /**
   * Why the URL's candidate is not the one being judged, said rather than hidden. A filter change
   * drops the baseline and keeps the candidate (url-state's rule), and the backend's re-picked
   * baseline can be that very cohort — which is in the table, so "not in this selection" was false.
   */
  readonly candidateNote = computed<'baseline' | 'absent' | null>(() => {
    const chosen = this.store.judgeCandidate();
    if (chosen === null || this.candidateKeys().includes(chosen)) { return null; }
    return chosen === this.baseline() ? 'baseline' : 'absent';
  });

  /**
   * The verdicts, only while they answer the question on screen. A result for the previous
   * axis, baseline or candidate is a different comparison, and leaving it up until the next
   * answer lands would put one pair's verdict beside another pair's deltas.
   */
  readonly judge = computed(() => {
    const j = this.store.judge();
    const p = this.page();
    return j !== null && j.groupBy === p.groupBy && j.baseline === p.baseline
      && j.candidate === this.candidate() ? j : null;
  });

  readonly lines = computed<JudgeLine[]>(() => {
    const rows = this.judge()?.rows ?? [];
    const firstCode = rows.findIndex(r => r.scope === 'code');
    return rows.map((row, i) => ({
      row,
      label: row.scope === 'total' ? 'All findings'
        : row.scope === 'plane' ? PLANE_LABELS[row.key as Plane] ?? row.key
        : row.key,
      code: row.scope === 'code',
      firstCode: i === firstCode,
      ratio: ratioText(row),
      corrected: row.rateRatio !== null && (row.baselineCount === 0 || row.candidateCount === 0),
    }));
  });

  readonly anyCorrected = computed(() => this.lines().some(l => l.corrected));

  constructor() {
    // The judge follows the table, never the filters directly: it needs the baseline the table
    // was answered with, and a candidate checked against that table's cohorts. Asking on the
    // filter change itself would send the previous baseline into a selection that may not hold
    // it — a 400 in the error bar for a request the reader never made.
    effect(() => {
      const p = this.page();
      const candidate = this.candidate();
      if (p.baseline === null || candidate === null) { return; }
      const baseline = p.baseline;
      untracked(() => this.store.loadJudge(p.groupBy as FacetKey, baseline, candidate));
    });
  }

  changeCandidate(key: string): void {
    this.url.patch({ candidate: key === '' ? null : key });
  }

  verdictLabel(v: JudgeVerdict): string { return VERDICT_LABELS[v]; }

  /** "φ 5.70 · df 6.2": the widening and the degrees of freedom behind the interval. */
  spreadText(r: JudgeRow): string {
    const phi = `φ ${r.dispersion.toFixed(2)}`;
    return r.degreesOfFreedom == null ? phi : `${phi} · df ${r.degreesOfFreedom.toFixed(1)}`;
  }

  spreadTip(r: JudgeRow): string {
    const df = r.rateRatio === null
      ? 'no ratio, so no interval'
      : r.degreesOfFreedom == null
        ? 'nothing beyond chance was estimated, so the interval uses the normal quantile'
        : r.degreesOfFreedom < 1
          ? 'too few sessions carry this failure (fewer than three, or a cohort of one session) to estimate its spread, so there is no interval'
          : `the interval's t quantile has ${r.degreesOfFreedom.toFixed(1)} degrees of freedom`;
    return `dispersion φ ${r.dispersion.toFixed(2)} widens the interval; ${df}`;
  }

  /** A null rate is an absence: n/a, never 0. */
  rateText(v: number | null): string { return v === null ? 'n/a' : v.toFixed(2); }

  fmt = (n: number): string => n.toLocaleString('en-US');
}

/**
 * The rate ratio with its interval: "5.05× [1.66–15.32]". A ratio with no interval prints
 * alone. A bound past a factor of a thousand prints as "<0.01" or ">999": on a t quantile with one
 * or two degrees of freedom the upper end can run to nine digits, and the digits say nothing the
 * inequality does not. No ratio is n/a: the backend sends none when neither side had the failure, or when a
 * side has no observed calls (verdict `no-data`). A side with a zero count does get a ratio —
 * Haldane-corrected, which the table marks.
 */
export function ratioText(r: JudgeRow): string {
  if (r.rateRatio === null) { return 'n/a'; }
  const ratio = `${r.rateRatio.toFixed(2)}×`;
  return r.ratioLow === null || r.ratioHigh === null
    ? ratio
    : `${ratio} [${bound(r.ratioLow)}–${bound(r.ratioHigh)}]`;
}

function bound(v: number): string {
  return v < 0.01 ? '<0.01' : v > 999 ? '>999' : v.toFixed(2);
}
