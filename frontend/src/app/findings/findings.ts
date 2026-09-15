import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Placeholder route component. Task 8 replaces this file entirely with the real
 * Findings table and its evidence detail panel; until then it renders exactly one
 * heading and nothing else, so the shell and routing can be verified first.
 */
@Component({
  selector: 'app-findings',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h1>Findings</h1>`,
})
export class Findings {}
