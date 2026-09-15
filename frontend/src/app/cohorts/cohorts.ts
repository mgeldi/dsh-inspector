import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
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
    // The shell loads overview and findings for the rail; the cohorts screen
    // owns its own one request, and only fires it when the route is entered.
    this.store.loadCohorts(this.groupBy());
  }

  /**
   * Axis change: one request, no baseline. The backend re-chooses the default
   * for the new axis and the baseline select follows the response — a stale
   * baseline key from the old axis is a 400 the UI must not send.
   */
  changeGroup(key: string): void {
    this.groupBy.set(key);
    this.store.loadCohorts(key);
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
   * rate. The sign is the information; the colour is deliberately the neutral
   * accent, because red/green would claim good/bad, which is a stance the
   * design refuses to take.
   */
  deltaText(v: number | null): string {
    if (v === null) { return 'n/a'; }
    const abs = Math.abs(v).toFixed(2);
    if (v > 0) { return `+${abs}`; }
    if (v < 0) { return `-${abs}`; }
    return abs;
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
