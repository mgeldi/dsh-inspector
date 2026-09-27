import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, untracked } from '@angular/core';
import type { CohortRow } from '../api/types';
import { DEFAULT_PRESET, FACET_KEYS, facetIsSet, withoutFacet, type FacetKey } from '../state/filters';
import { InsightsStore } from '../state/insights.store';
import { ViewUrl } from '../state/view-url';
import { CohortJudge } from './judge';

/**
 * The axes the backend whitelists, in the order the select lists them. The values are the
 * backend keys, verbatim: `groupBy` is a fixed whitelist there, so a friendly alias that
 * re-maps back would be a seam with no purpose. They are also the rail's facet keys, which is
 * what lets the rail dim the facet this screen is grouping by.
 *
 * Provider and role are here because model alone cannot answer the question the screen
 * exists for: the orchestrator and its subagents can be served under one model id, and only
 * the provider or the delegation role separates them into two cohorts.
 */
const GROUP_KEYS: readonly { key: FacetKey; label: string }[] = [
  { key: 'harnessVersion', label: 'Harness version' },
  { key: 'model', label: 'Model' },
  { key: 'provider', label: 'Provider' },
  { key: 'role', label: 'Role' },
  { key: 'schema', label: 'Schema' },
  { key: 'preset', label: 'Preset' },
];

/**
 * One group of columns: a count, its rate per 1,000 observed calls and the rate's delta.
 * All findings first, then the three planes in the fixed order the overview uses. A single
 * all-findings rate hides which plane moved — a harness change that trades guard refusals
 * for misuse reads as "no change" — so every plane gets its own rate and its own delta.
 */
interface RateGroup {
  key: string;
  label: string;
  count: (r: CohortRow) => number;
  rate: (r: CohortRow) => number | null;
  delta: (r: CohortRow) => number | null;
}

const RATE_GROUPS: readonly RateGroup[] = [
  {
    key: 'all', label: 'All findings',
    count: r => r.findings, rate: r => r.findingsPerKCalls, delta: r => r.findingsPerKCallsDelta,
  },
  {
    key: 'guard', label: 'Guard',
    count: r => r.guardFindings, rate: r => r.violationRatePerK, delta: r => r.violationRatePerKDelta,
  },
  {
    key: 'misuse', label: 'Model misuse',
    count: r => r.misuseFindings, rate: r => r.misuseRatePerK, delta: r => r.misuseRatePerKDelta,
  },
  {
    key: 'infra', label: 'Infrastructure',
    count: r => r.infraFindings, rate: r => r.infraRatePerK, delta: r => r.infraRatePerKDelta,
  },
];

/**
 * The cohorts comparison: one axis at a time, rates per 1,000 *observed* tool
 * calls, and the deltas in percentage points against the baseline cohort.
 *
 * The component renders the store and triggers its own fetch (the judge below the
 * table triggers its own); it never builds a query and never injects HttpClient. The basis note is the backend's own
 * sentence about what the table is allowed to claim — it is rendered as a
 * visible banner, because a one-row comparison silently shown as a regression
 * analysis has answered a question it was not asked.
 */
@Component({
  selector: 'app-cohorts',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CohortJudge],
  templateUrl: './cohorts.html',
  styleUrl: './cohorts.scss',
})
export class Cohorts {
  readonly store = inject(InsightsStore);
  private readonly url = inject(ViewUrl);
  readonly groupKeys = GROUP_KEYS;
  readonly rateGroups = RATE_GROUPS;

  /** The axis the request is carrying, from the URL. A bare /cohorts is version over version. */
  readonly groupBy = computed(() => this.store.cohortGroupBy());

  readonly page = computed(() => this.store.cohorts());

  /**
   * The axis the Group-by select shows: the one the table on screen is grouped by. While a new
   * axis loads it is the URL's, the choice just made; once that request has failed the table
   * below is still the previous axis's, and a select naming the new one above it put five model
   * rows under "Role".
   */
  readonly shownAxis = computed(() => {
    const p = this.page();
    return p !== null && this.store.failed('cohorts') ? p.groupBy : this.groupBy();
  });
  readonly loaded = computed(() => this.page() !== null);
  readonly rows = computed<CohortRow[]>(() => this.page()?.cohorts ?? []);
  readonly baseline = computed<string>(() => this.page()?.baseline ?? '');
  readonly basisNote = computed<string | null>(() => this.page()?.basisNote ?? null);
  readonly baselineKeys = computed<string[]>(() => this.rows().map(r => r.key));

  /**
   * Whether the rail narrows the selection. An empty table under a filter is "nothing matches",
   * not "the index is empty": telling that reader to run the indexer sends them to fix the one
   * thing that is not broken.
   */
  readonly filtered = computed(() => {
    // The axis facet is not applied on this screen, so it does not count as narrowing it.
    const f = withoutFacet(this.store.filters(), this.groupBy());
    return f.presetId !== DEFAULT_PRESET || FACET_KEYS.some(key => facetIsSet(f, key));
  });

  constructor() {
    // The axis is published to the store because it changes what the rail means: on this screen
    // one of the facets is the grouping axis, and filtering by the axis you are grouping on
    // always leaves exactly one cohort. The rail dims that facet and says the filter is not
    // applied here. Cleared on teardown, so the overview's rail is not left missing a facet
    // nobody is grouping by.
    effect(() => this.store.cohortAxis.set(this.groupBy()));
    inject(DestroyRef).onDestroy(() => this.store.cohortAxis.set(null));

    // The shell loads overview and findings for the rail; this screen owns its own requests.
    // Reading the filters, the axis and the baseline here is what subscribes the table to them:
    // a filter change has to re-ask, and a table that quietly kept the unfiltered numbers would
    // be the same lie in a new place. The URL drops the baseline on a filter or axis change
    // (url-state's rule), so the backend re-picks the busiest cohort and the basis note says so.
    effect(() => {
      const groupBy = this.groupBy();
      const baseline = this.store.cohortBaseline();
      this.store.filters();
      untracked(() => this.store.loadCohorts(groupBy, baseline ?? undefined));
    });
  }

  /**
   * Axis change: a navigation, and the effect above does the fetch — so a filter change, an axis
   * change and a pasted link all load through the same path. The URL drops the baseline and the
   * candidate with it: both name cohorts of the previous axis.
   */
  changeGroup(key: string): void {
    this.url.patch({ groupBy: key as FacetKey });
  }

  /** The baseline the server refused for this screen's newest request, if it refused one. */
  readonly staleBaseline = computed<string | null>(() => {
    const r = this.store.rejected();
    return r !== null && r.lane === 'cohorts' && r.filter === 'baseline' ? r.value : null;
  });

  /** Ask again for the table the URL describes, after a failure that was not a refused baseline. */
  retry(): void {
    this.store.loadCohorts(this.groupBy(), this.store.cohortBaseline() ?? undefined);
  }

  /** Drop the refused baseline: the backend then picks the busiest cohort and the note says so. */
  resetBaseline(): void {
    this.url.patch({ baseline: null });
  }

  /** A new baseline that is the current candidate would judge a cohort against itself. */
  changeBaseline(key: string): void {
    this.url.patch(key === this.store.judgeCandidate() ? { baseline: key, candidate: null } : { baseline: key });
  }

  /** A null rate is an absence: n/a, never 0. 0/0 is not a measurement. */
  rateText(v: number | null): string {
    return v === null ? 'n/a' : v.toFixed(2);
  }

  /**
   * A delta in the rate's own units — percentage points, not a percentage.
   * Signed when non-zero: an unsigned number next to a rate reads as a second
   * rate. The sign stays in the text, and the colour follows direction too —
   * every rate is better lower, so negative is an improvement (accent) and
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
   * improvement (lower is better on every rate), zero = muted, na = italic absence.
   */
  deltaKind(v: number | null): 'up' | 'down' | 'zero' | 'na' {
    if (v === null) { return 'na'; }
    if (v > 0) { return 'up'; }
    if (v < 0) { return 'down'; }
    return 'zero';
  }

  isBaselineRow(key: string): boolean {
    return key === this.baseline() && this.baseline() !== '';
  }

  /** Every count is a measurement: tabular numerals with separators. */
  fmt = (n: number): string => n.toLocaleString('en-US');
}
