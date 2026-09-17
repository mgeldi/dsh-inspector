import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { toParams, type UrlState } from './url-state';

/**
 * The one place a control changes what is on screen.
 *
 * <p>Controls used to write the store and call a load in the same breath, which made the URL a
 * decoration: the screen knew what it was showing and the address bar did not, so a filtered
 * view could not be linked to, a reload lost it, and the back button moved the history without
 * moving the screen. Now a control navigates and nothing else; the shell reads the URL back and
 * feeds the store. One direction, and the address bar cannot fall out of step with the data
 * because it is upstream of it.
 *
 * <p>`merge` keeps the parameters a control did not mention, which is what makes these calls
 * composable — changing the sort must not silently clear the rail. Clearing is explicit: a
 * null value removes its key.
 */
@Injectable({ providedIn: 'root' })
export class ViewUrl {
  private readonly router = inject(Router);

  /**
   * Change part of the view. `replace` is for changes that are not worth a history entry of
   * their own — landing on a screen that had to correct the URL it was given, rather than a
   * move the user made and might want to undo.
   */
  patch(state: Partial<UrlState>, replace = false): void {
    void this.router.navigate([], {
      queryParams: toParams(state),
      queryParamsHandling: 'merge',
      replaceUrl: replace,
    });
  }

  /** Move to another screen, carrying a change with it — the overview's drill-down into findings. */
  go(path: readonly string[], state: Partial<UrlState>): void {
    void this.router.navigate(path as string[], {
      queryParams: toParams(state),
      queryParamsHandling: 'merge',
    });
  }
}
