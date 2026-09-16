import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import { InsightsStore } from '../state/insights.store';
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
  private readonly destroyRef = inject(DestroyRef);

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
    // The rail lives in the shell and needs the vocabulary, so the shell starts the
    // one shared load; the routes render whatever the store holds and never fetch
    // anything of their own in Task 6.
    this.store.loadAll();
  }

  tabActive(key: string): boolean {
    const segment = this.currentUrl().split('/').filter(Boolean)[0] ?? '';
    return key === 'overview' ? segment === '' : segment === key;
  }

  reindex(): void { this.store.reindex(); }
  dismissError(): void { this.store.dismissError(); }
}
