import { signal } from '@angular/core';
import { InsightsStore } from './insights.store';
import { applyPreset } from './filters';
import type { UrlState } from './url-state';

/**
 * A stand-in for the shell's URL loop, for specs that drive one component.
 *
 * <p>Since the controls navigate instead of writing the store, a component on its own no
 * longer produces a request — the shell is what reads the new URL back and asks for the
 * load. A component spec that stopped at "did it navigate" would pass while the screen
 * showed nothing, so this closes the same loop the shell closes: apply the patch to the
 * store, then load what changed. The patches are recorded as well, so a spec can assert the
 * navigation itself where that is the interesting part.
 *
 * <p>It is a double of the shell, not of the router, and `ShellUrlLoopSpec` is what proves
 * the real one behaves this way.
 */
export class FakeViewUrl {
  readonly patches = signal<Partial<UrlState>[]>([]);

  constructor(private readonly store: InsightsStore) {}

  patch(state: Partial<UrlState>): void {
    this.patches.update(p => [...p, state]);
    this.apply(state);
  }

  go(_path: readonly string[], state: Partial<UrlState>): void {
    this.patch(state);
  }

  /** The last patch, which is what a spec usually wants to look at. */
  last(): Partial<UrlState> | undefined {
    const all = this.patches();
    return all[all.length - 1];
  }

  private apply(state: Partial<UrlState>): void {
    let shared = false;
    if (state.filters !== undefined) {
      this.store.filters.set(state.filters);
      shared = true;
    }
    if (state.code !== undefined) { this.store.code.set(state.code); }
    if (state.sort !== undefined) { this.store.sort.set(state.sort); }
    if (state.page !== undefined) { this.store.page.set(state.page); }
    if (state.size !== undefined) { this.store.size.set(state.size); }

    if (shared) { this.store.loadAll(); } else { this.store.loadFindings(); }
  }
}

/** The 'all time' range, spelled once so specs do not each rebuild it. */
export function allTimeFilters(store: InsightsStore) {
  return applyPreset(store.filters(), 'all');
}
