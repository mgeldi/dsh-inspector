import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * The root renders nothing but the outlet: the Shell is the parent route (see
 * app.routes.ts) and owns the toolbar, the filter rail and the inner outlet the
 * feature routes mount into.
 */
@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
})
export class App {}
