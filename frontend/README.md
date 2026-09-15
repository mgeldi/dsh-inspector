# Frontend

The Angular 22 app of DSH Inspector: three lazy routes (Overview, Findings, Cohorts)
behind one shell, all of them reading a single `InsightsStore` that owns every fetch.

Generated with [Angular CLI](https://github.com/angular/angular-cli) version 22.1.8.

## Running it

The dev server runs on **4300** — not the CLI default — and proxies `/api/*` to the
backend on **8091** via `proxy.conf.json`. The port is a design constraint of the whole
project (the root README explains why), and the proxy is what makes the dashboard and
the API same-origin in development.

Easiest start is the `./run.sh` at the repository root, which runs both sides. To run
only the frontend against an already-running backend:

```bash
npm install
npm start          # ng serve, http://127.0.0.1:4300
```

## Tests

```bash
npm test -- --watch=false
```

Vitest 4 on jsdom, run through the Angular builder. The tests drive the store rather
than the component, and flush the store's pending requests through
`HttpTestingController` with a URL predicate (`r => r.url === '/api/findings'`), because
a string matcher in this Angular version does not match query params.

## Conventions that are load-bearing

- **The store owns every fetch.** Components never inject `HttpClient` and never build
  query params; one `filters` signal feeds every request, so no two screens can describe
  different queries. A route that wants its own data (Cohorts) triggers exactly one load
  through the store when the route is entered.
- **Standalone components, signals, OnPush, zoneless.** Routes are lazy, so each screen
  is a chunk: Overview carries echarts, Findings and Cohorts are plain tables and stay
  small for it.
- **Theme tokens, not the theme, in component styles.** A component's SCSS `@use`s
  `src/theme-tokens.scss` — variables only, emits no CSS — never `theme.scss`, which
  re-emits the whole Material theme into every component stylesheet and breaks the
  per-component style budget. No hex colour literals in components; plane colours come
  from `charts/theme.ts` so chip, chart and marker cannot disagree.
- **Charts go through `charts/chart.ts`**, which owns init/dispose/resize. A spec that
  imports the wrapper mocks `echarts/*` (jsdom has no canvas).

## Notes

Material 22.1.6 exports **both** NgModule and standalone-component names per entry point
(e.g. `@angular/material/card` exports `MatCardModule` and `MatCard`), and a probe build
importing `MatCardModule` + `MatToolbarModule` compiled clean — so this project uses the
**NgModule import form** (`MatXxxModule` in `imports`/component metadata) everywhere.

## Building

```bash
npm run build
```

Compiles into `dist/`. The initial bundle is about 300 kB raw (about 80 kB transfer);
Findings and Cohorts are lazy chunks (about 60 kB and 8 kB raw), because their tables and
the detail panel do not belong in the first load.

## Code scaffolding

```bash
ng generate component component-name
```

For the full list of schematics (`components`, `directives`, `pipes`, …):

```bash
ng generate --help
```

More on the Angular CLI, including command references:
[Angular CLI Overview and Command Reference](https://angular.dev/tools/cli).
