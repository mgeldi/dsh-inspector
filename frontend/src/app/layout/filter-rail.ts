import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { InsightsStore } from '../state/insights.store';
import {
  applyPreset, emptyFilters, FACET_KEYS, facetIsSet, PRESETS, type FacetKey, type PresetId,
} from '../state/filters';
import { ViewUrl } from '../state/view-url';

type VocabKey = 'schemas' | 'models' | 'providers' | 'roles' | 'presets' | 'harnessVersions';

interface Facet {
  key: FacetKey;
  label: string;
  vocabKey: VocabKey;
}

/**
 * The facets of §7's shared filter contract, in FACET_KEYS order. Time is preset-only
 * below — no datepicker, §8 cut it deliberately.
 *
 * Provider is what tells two deployments of one model id apart, and role is the session's
 * place in a delegation (orchestrator or subagent) — two cohorts that share a model name.
 *
 * `unknown` is an ordinary vocabulary value here: it is the bucket for sessions
 * without a request/context (or, for role, without a delegation depth), and its option
 * label is rendered verbatim. Dropping it would drop those sessions from every filtered
 * view, so it is never filtered out.
 */
const FACET_LABELS: Record<FacetKey, { label: string; vocabKey: VocabKey }> = {
  schema: { label: 'Schema', vocabKey: 'schemas' },
  model: { label: 'Model', vocabKey: 'models' },
  provider: { label: 'Provider', vocabKey: 'providers' },
  role: { label: 'Role', vocabKey: 'roles' },
  preset: { label: 'Preset', vocabKey: 'presets' },
  harnessVersion: { label: 'Harness version', vocabKey: 'harnessVersions' },
};
const FACETS: readonly Facet[] = FACET_KEYS.map(key => ({ key, ...FACET_LABELS[key] }));

/**
 * The vocabulary-driven filter rail. Every control reads `store.vocabulary()` and
 * navigates through ViewUrl; it never constructs a query string and never injects
 * HttpClient — the shell turns the URL into store loads, and the store owns fetching.
 */
@Component({
  selector: 'app-filter-rail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './filter-rail.html',
  styleUrl: './filter-rail.scss',
})
export class FilterRail {
  readonly store = inject(InsightsStore);
  private readonly url = inject(ViewUrl);
  readonly facets = FACETS;
  readonly presets = PRESETS;

  /**
   * Whether anything is actually being filtered. "Clear filters" is a no-op with an empty
   * filter set, and a control that is always live teaches the user that its state means
   * nothing; the disabled state doubles as the answer to "am I looking at everything?".
   *
   * <p>It reads the store's count, the one the toolbar badge shows, so the badge and the button
   * cannot disagree. They used to: the button looked at the facets alone while the badge also
   * counted a code or detector drill-down, so a view narrowed only by a drill-down said "1
   * active" beside a Clear that would not click.
   */
  readonly hasActiveFilters = computed(() => this.store.activeFilterCount() > 0);

  /**
   * The facet's values from the store's vocabulary, plus — if the active filter value
   * is no longer in the vocabulary — that value too. A stale filter must stay visible
   * so the user can clear it, not vanish from the select and keep filtering silently.
   */
  facetValues(facet: Facet): string[] {
    const values = this.store.vocabulary()?.[facet.vocabKey] ?? [];
    const current = this.store.filters()[facet.key];
    if (current !== null && current !== undefined && !values.includes(current)) {
      return [...values, current];
    }
    return values;
  }

  /**
   * A facet whose vocabulary has exactly one value is rendered disabled with its
   * value shown, not hidden: hiding it makes the single-version case look like a
   * broken filter, and that is exactly the situation a reviewer of this project is in.
   */
  facetIsSingle(facet: Facet): boolean {
    return this.facetValues(facet).length === 1;
  }

  /**
   * The facet the cohorts screen is currently grouping by, if it is open. Grouping by a facet
   * and filtering by that same facet always yields a one-row table whose delta against itself is
   * zero — a result that looks like a finding and means nothing. The control is dimmed with a
   * reason instead, because "disabled" without a reason is indistinguishable from broken.
   */
  facetIsInert(facet: Facet): boolean {
    return this.store.cohortAxis() === facet.key;
  }

  /**
   * Why the facet is dimmed. A value set before the axis was chosen stays in the URL — it is the
   * reader's choice for the other screens — but the cohort and judge requests leave it out, and
   * a disabled control still showing a value would otherwise read as a filter in force.
   */
  facetInertNote(facet: Facet): string {
    return facetIsSet(this.store.filters(), facet.key)
      ? 'Cohorts are grouped by this, so this filter is not applied here. It still narrows Overview and Findings.'
      : 'Cohorts are grouped by this — filtering it would leave one row.';
  }

  /** What the select displays: the single value for a fixed facet, else the active filter. */
  facetShownValue(facet: Facet): string {
    if (this.facetIsSingle(facet)) {
      return this.facetValues(facet)[0] ?? '';
    }
    return this.store.filters()[facet.key] ?? '';
  }

  /**
   * One facet change: one write through the store, one reload of everything the
   * shared filters describe. Absence, not an empty string, is what means "not
   * filtered" — the backend reads '' as a value and answers 400.
   */
  changeFacet(facet: Facet, value: string): void {
    // Back to the first page: page 7 of a wider selection is not page 7 of a narrower one,
    // and a filter that leaves the reader stranded past the end of its own result reads as
    // "no findings" — the answer this screen must never give by accident.
    this.url.patch({
      filters: { ...this.store.filters(), [facet.key]: value === '' ? null : value },
      page: 0,
    });
  }

  changePreset(id: PresetId): void {
    this.url.patch({ filters: applyPreset(this.store.filters(), id), page: 0 });
  }

  clear(): void {
    this.url.patch({ filters: emptyFilters(), code: null, detector: null, page: 0 });
  }
}
