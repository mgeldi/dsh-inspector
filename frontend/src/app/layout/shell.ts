import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import { InsightsStore } from '../state/insights.store';
import { findingsAxisDiffers, fromParams, sharedFiltersDiffer, type UrlState } from '../state/url-state';
import { FilterRail } from './filter-rail';

/**
 * The app frame, and the parent route for the three feature routes. Toolbar carries
 * the product name, the tabs, the re-index action with its one-line result, the busy
 * progress bar and the dismissible error bar. The filter rail is a sibling of the
 * outlet, not a child of it: switching tabs keeps the filters, which is the visible
 * consequence of §7's one filter contract.
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterOutlet, MatToolbarModule, MatProgressBarModule, FilterRail],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  readonly store = inject(InsightsStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  /** The last state the URL described, so a change can be told apart from a repetition. */
  private applied: UrlState | null = null;

  readonly tabs = [
    { key: 'overview', label: 'Overview', link: '' },
    { key: 'findings', label: 'Findings', link: 'findings' },
    { key: 'cohorts', label: 'Cohorts', link: 'cohorts' },
  ] as const;

  /**
   * The active tab, fed by the router's own events. The zoneless shell is OnPush and
   * no navigation event wakes change detection on its own, so the shell listens for
   * NavigationEnd and writes a signal; reading that signal in the template is what
   * keeps the underline on the right tab.
   */
  readonly currentUrl = signal('/');

  /**
   * The one-line re-index result, or null until the first successful run. The pruned count
   * rides along only when it is non-zero: a run that quietly discarded rows is the same class
   * of problem as one that quietly kept them, so when rows went, the line says so.
   *
   * `evidenceRows` is on the payload and deliberately not in this sentence. The line has one
   * fold of room and `pruned` is in it because it reports a loss; an evidence count reports what
   * is present, and the finding drawer is where a user finds out whether a finding has command
   * behind it. A count with no question attached to it is decoration on a notice that disappears.
   */
  readonly indexResult = computed(() => {
    const li = this.store.lastIndex();
    if (!li) { return null; }
    const run = `indexed ${li.streams} streams, ${li.findings} findings in ${(li.durationMs / 1000).toFixed(1)} s`;
    if (!li.pruned) { return run; }
    return `${run}, pruned ${li.pruned} ${li.pruned === 1 ? 'stream' : 'streams'}`;
  });

  constructor() {
    this.router.events.pipe(
      filter(e => e instanceof NavigationEnd),
      map(e => (e as NavigationEnd).urlAfterRedirects),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(url => this.currentUrl.set(url));
    // The URL is upstream of the data. The shell reads it, writes the store, and asks for
    // exactly the loads the change requires — so the initial load, a shared link, a reload
    // and the back button all arrive through the same path instead of three of them being
    // special cases. The rail lives in the shell and needs the vocabulary, which the
    // overview load brings with it.
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(map => this.applyUrl(fromParams(key => map.get(key))));
  }

  /**
   * Feed the store from the URL, then load what actually changed. A page change must not
   * re-ask the overview: it describes the same population as before, and the cohorts screen
   * re-asks itself through the filters signal it already watches.
   */
  private applyUrl(next: UrlState): void {
    const previous = this.applied;
    this.applied = next;

    this.store.filters.set(next.filters);
    this.store.code.set(next.code);
    this.store.sort.set(next.sort);
    this.store.page.set(next.page);
    this.store.size.set(next.size);

    if (previous === null || sharedFiltersDiffer(previous, next)) {
      this.store.loadAll();
      return;
    }
    if (findingsAxisDiffers(previous, next)) {
      this.store.loadFindings();
    }
  }

  tabActive(key: string): boolean {
    const segment = this.currentUrl().split('/').filter(Boolean)[0] ?? '';
    return key === 'overview' ? segment === '' : segment === key;
  }

  reindex(): void { this.store.reindex(); }
  dismissError(): void { this.store.dismissError(); }
  dismissNotice(): void { this.store.dismissNotice(); }
}
