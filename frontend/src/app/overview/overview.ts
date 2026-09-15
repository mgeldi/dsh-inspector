import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Placeholder route component. Task 7 replaces this file entirely with the real
 * Overview (tiles, plane mix, daily series, detector ranking, throughput table);
 * until then it renders exactly one heading and nothing else, so the shell and
 * routing can be verified before any dashboard content exists.
 */
@Component({
  selector: 'app-overview',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h1>Overview</h1>`,
})
export class Overview {}
