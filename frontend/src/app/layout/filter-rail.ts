import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { InsightsStore } from '../state/insights.store';
import { PRESETS, type Filters, type PresetId } from '../state/filters';

type FacetKey = 'schema' | 'model' | 'preset' | 'harnessVersion';
type VocabKey = 'schemas' | 'models' | 'presets' | 'harnessVersions';

interface Facet {
  key: FacetKey;
  label: string;
  vocabKey: VocabKey;
}

/**
 * The four facets of §7's shared filter contract, in the order the rail shows them.
 * Time is preset-only below — no datepicker, §8 cut it deliberately.
 *
 * `unknown` is an ordinary vocabulary value here: it is the bucket for sessions
 * without a request/context, and its option label is rendered verbatim. Dropping it
 * would drop those sessions from every filtered view, so it is never filtered out.
 */
const FACETS: readonly Facet[] = [
  { key: 'schema', label: 'Schema', vocabKey: 'schemas' },
  { key: 'model', label: 'Model', vocabKey: 'models' },
  { key: 'preset', label: 'Preset', vocabKey: 'presets' },
  { key: 'harnessVersion', label: 'Harness version', vocabKey: 'harnessVersions' },
];

/**
 * The vocabulary-driven filter rail. Every control reads `store.vocabulary()` and
 * writes through the store; it never constructs a query string and never injects
 * HttpClient — the store owns fetching, which is why it exists.
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
  readonly facets = FACETS;
  readonly presets = PRESETS;

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
    this.store.setFilters({ [facet.key]: value === '' ? null : value } as Partial<Filters>);
    this.store.loadAll();
  }

  changePreset(id: PresetId): void {
    this.store.setPreset(id);
    this.store.loadAll();
  }

  clear(): void {
    this.store.clearFilters();
    this.store.loadAll();
  }
}
