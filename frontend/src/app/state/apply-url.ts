import type { InsightsStore } from './insights.store';
import { DEFAULT_GROUP_BY, findingsAxisDiffers, sharedFiltersDiffer, type UrlState } from './url-state';

/**
 * Feed the store from the state a URL describes, then load what actually changed. The shell
 * runs this on every URL change; the component specs' URL double runs the same function, so
 * the loop a spec exercises is the loop the app runs rather than a sketch of it.
 *
 * <p>The filters are written only when the population changed. Every URL change parses a fresh
 * filters object, and the cohorts screen watches that signal: writing it unconditionally made a
 * candidate change — a query key no population depends on — re-ask the cohort table, and the new
 * table re-ask the judge, three requests for one verdict. A same-preset window whose ends moved
 * with the clock is the same population too; it is what `sharedFiltersDiffer` already ignores.
 *
 * <p>A page change must not re-ask the overview: it describes the same population as before.
 * The cohorts screen re-asks itself from the signals it watches, so nothing here loads it.
 */
export function applyUrlState(store: InsightsStore, previous: UrlState | null, next: UrlState): void {
  const population = previous === null || sharedFiltersDiffer(previous, next);
  if (population) {
    store.filters.set(next.filters);
  }
  store.code.set(next.code);
  store.detector.set(next.detector);
  store.sort.set(next.sort);
  store.page.set(next.page);
  store.size.set(next.size);
  store.cohortGroupBy.set(next.groupBy ?? DEFAULT_GROUP_BY);
  store.cohortBaseline.set(next.baseline);
  store.judgeCandidate.set(next.candidate);

  if (population) {
    store.loadAll();
  } else if (findingsAxisDiffers(previous, next)) {
    store.loadFindings();
  }
}
