import { Routes } from '@angular/router';
import { Shell } from './layout/shell';

/**
 * The shell is the parent route; all three feature routes load lazily via
 * loadComponent. Task 5 measured the consequence of the opposite: with the chart
 * wrapper in the entry graph the initial bundle is 767.99 kB raw, and with lazy
 * routes it stays at 225.38 kB, because ECharts rides in the Overview chunk that
 * only loads when Overview is opened. The tabs live in the shell, so a route
 * change swaps only the outlet — the filter rail, a sibling of it, survives.
 */
export const routes: Routes = [
  {
    path: '',
    component: Shell,
    children: [
      { path: '', loadComponent: () => import('./overview/overview').then(m => m.Overview) },
      { path: 'findings', loadComponent: () => import('./findings/findings').then(m => m.Findings) },
      { path: 'cohorts', loadComponent: () => import('./cohorts/cohorts').then(m => m.Cohorts) },
      { path: '**', redirectTo: '' },
    ],
  },
];
