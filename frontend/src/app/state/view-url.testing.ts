import { signal } from '@angular/core';
import { applyUrlState } from './apply-url';
import { InsightsStore } from './insights.store';
import { applyPreset } from './filters';
import { fromParams, toParams, type UrlParams, type UrlState } from './url-state';

/**
 * A stand-in for the shell's URL loop, for specs that drive one component.
 *
 * <p>Since the controls navigate instead of writing the store, a component on its own no
 * longer produces a request — the shell is what reads the new URL back and asks for the
 * load. A component spec that stopped at "did it navigate" would pass while the screen
 * showed nothing, so this closes the same loop the shell closes. The patches are recorded as
 * well, so a spec can assert the navigation itself where that is the interesting part.
 *
 * <p>It does what the router and the shell do together, with the same functions: the query
 * string the store currently describes is written out with `toParams`, the patch is merged
 * into it the way `queryParamsHandling: 'merge'` merges (a null removes a key), the result is
 * parsed back with `fromParams`, and `applyUrlState` — the shell's own — feeds the store and
 * loads what changed. An earlier version applied each patch field by field and wrote the
 * filters whenever a patch carried them; the real shell wrote them on every URL change, and a
 * cohorts spec could not see the redundant requests that difference caused.
 *
 * <p>The one thing it does not model is the router itself: the "current URL" is rebuilt from
 * the store on every patch, so a spec that writes store signals directly is treated as if the
 * URL said so. `ShellUrlLoopSpec` drives the real router.
 */
export class FakeViewUrl {
  readonly patches = signal<Partial<UrlState>[]>([]);

  constructor(private readonly store: InsightsStore) {}

  patch(state: Partial<UrlState>): void {
    this.patches.update(p => [...p, state]);
    const current = this.current();
    const params: UrlParams = { ...toParams(current), ...toParams(state) };
    applyUrlState(this.store, current, fromParams(key => params[key] ?? null));
  }

  go(_path: readonly string[], state: Partial<UrlState>): void {
    this.patch(state);
  }

  /** The last patch, which is what a spec usually wants to look at. */
  last(): Partial<UrlState> | undefined {
    const all = this.patches();
    return all[all.length - 1];
  }

  /** The state the URL describes right now, which in the app is always what the store holds. */
  private current(): UrlState {
    const s = this.store;
    return {
      filters: s.filters(), code: s.code(), detector: s.detector(), sort: s.sort(),
      page: s.page(), size: s.size(), groupBy: s.cohortGroupBy(),
      baseline: s.cohortBaseline(), candidate: s.judgeCandidate(),
    };
  }
}

/** The 'all time' range, spelled once so specs do not each rebuild it. */
export function allTimeFilters(store: InsightsStore) {
  return applyPreset(store.filters(), 'all');
}
