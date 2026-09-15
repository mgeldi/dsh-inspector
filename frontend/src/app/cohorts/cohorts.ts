import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Placeholder route component. Task 9 (stretch) replaces this file entirely with
 * the real Cohorts comparison; until then it renders exactly one heading and
 * nothing else, so the shell and routing can be verified first.
 */
@Component({
  selector: 'app-cohorts',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h1>Cohorts</h1>`,
})
export class Cohorts {}
