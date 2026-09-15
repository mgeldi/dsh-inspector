import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import type { CohortRow } from '../api/types';
import { InsightsStore } from '../state/insights.store';

/**
 * The four axes the backend whitelists, in the order the select lists them.
 * The values are the backend keys, verbatim: `groupBy` is a fixed whitelist
 * there, so a friendly alias that re-maps back would be a seam with no purpose.
 */
const GROUP_KEYS: readonly { key: string; label: string }[] = [
  { key: 'harnessVersion', label: 'Harness version' },
  { key: 'model', label: 'Model' },
  { key: 'schema', label: 'Schema' },
  { key: 'preset', label: 'Preset' },
];

/**
 * The cohorts comparison: one axis at a time, rates per 1,000 *observed* tool
 * calls, and the deltas in percentage points against the baseline cohort.
 *
 * The component renders the store and triggers its one fetch; it never builds
 * a query and never injects HttpClient. The basis note is the backend's own
 * sentence about what the table is allowed to claim — it is rendered as a
 * visible banner, because a one-row comparison silently shown as a regression
 * analysis has answered a question it was not asked.
 */
@Component({
  selector: 'app-cohorts',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cohorts.html',
  styleUrl: './cohorts.scss',
})
export class Cohorts {
  readonly store = inject(InsightsStore);
  readonly groupKeys = GROUP_KEYS;

  /** The axis the request is carrying. First load: the operator question, version over version. */
  readonly groupBy = signal<string>('harnessVersion');

  readonly page = computed(() => this.store.cohorts());
  readonly loaded = computed(() => this.page() !== null);
  readonly rows = computed<CohortRow[]>(() => this.page()?.cohorts ?? []);
  readonly baseline = computed<string>(() => this.page()?.baseline ?? '');
  readonly basisNote = computed<string | null>(() => this.page()?.basisNote ?? null);
  readonly baselineKeys = computed<string[]>(() => this.rows().map(r => r.key));

  constructor() {
    // The axis is published to the store because it changes what the rail means: on this screen
    // one of the four facets is the grouping axis, and filtering by the axis you are grouping on
    // always leaves exactly one cohort. The rail dims that facet rather than offering a control
    // whose only outcome is a degenerate table. Cleared on teardown, so the overview's rail is
    // not left missing a facet nobody is grouping by.
    this.store.cohortAxis.set(this.groupBy());
    inject(DestroyRef).onDestroy(() => this.store.cohortAxis.set(null));

    // The shell loads overview and findings for the rail; this screen owns its own request.
    // Reading filters() inside the effect is what subscribes it to the rail: the cohorts
    // endpoints now honour the shared filter contract, so a filter change has to re-ask, and a
    // table that quietly kept the unfiltered numbers would be the same lie in a new place.
    // The baseline is deliberately not carried across a filter change — the cohort it named may
    // no longer exist in the selection, which the backend answers with a 400 — so the backend
    // re-picks the busiest cohort and the basis note says it did.
    effect(() => {
      this.store.filters();
      this.store.loadCohorts(this.groupBy());
    });
  }

  /**
   * Axis change: set the signals and let the effect fetch. It reads `groupBy` as well as the
   * filters, so a second explicit `loadCohorts` here would fire the same request twice — the
   * effect is this screen's only load trigger, which is also what makes a filter change and an
   * axis change behave identically. No baseline: a stale key from the previous axis is a 400 the
   * UI must not send, so the backend re-picks and the basis note says it did.
   */
  changeGroup(key: string): void {
    this.groupBy.set(key);
    this.store.cohortAxis.set(key);
  }

  changeBaseline(key: string): void {
    this.store.loadCohorts(this.groupBy(), key);
  }

  /** A null rate is an absence: n/a, never 0. 0/0 is not a measurement. */
  rateText(v: number | null): string {
    return v === null ? 'n/a' : v.toFixed(2);
  }

  /**
   * A delta in the rate's own units — percentage points, not a percentage.
   * Signed when non-zero: an unsigned number next to a rate reads as a second
   * rate. The sign stays in the text, and the colour follows direction too —
   * both rates are better lower, so negative is an improvement (accent) and
   * positive a regression (violation hue).
   */
  deltaText(v: number | null): string {
    if (v === null) { return 'n/a'; }
    const abs = Math.abs(v).toFixed(2);
    if (v > 0) { return `+${abs}`; }
    if (v < 0) { return `-${abs}`; }
    return abs;
  }

  /**
   * The delta's direction, which the cell paints: up = regression, down =
   * improvement (lower is better on both rates), zero = muted, na = italic absence.
   */
  deltaKind(v: number | null): 'up' | 'down' | 'zero' | 'na' {
    if (v === null) { return 'na'; }
    if (v > 0) { return 'up'; }
    if (v < 0) { return 'down'; }
    return 'zero';
  }

  /** Zero delta, muted: identical to the baseline is a statement, not an alarm. */
  deltaIsZero(v: number | null): boolean {
    return v !== null && v === 0;
  }

  isBaselineRow(key: string): boolean {
    return key === this.baseline() && this.baseline() !== '';
  }

  /** Every count is a measurement: tabular numerals with separators. */
  fmt = (n: number): string => n.toLocaleString('en-US');
}
