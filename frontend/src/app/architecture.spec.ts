// @vitest-environment node
/**
 * Structural rules, encoded as tests the way the backend's FilterContractTest does it.
 *
 * Each rule below has so far survived only because the README or a comment says so;
 * these make it fail the build instead. Every failure names the offending file and
 * says what to do about it — a rule that fails with "unexpected error" is not a rule.
 *
 * The spec reads the tree from disk (node:fs), so it is pinned to the node environment
 * via the docblock above rather than asking the whole suite for one.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// This file lives in src/app/, so the src root is one level up.
const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const APP = join(SRC, 'app');

function walk(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) { out.push(...walk(full)); }
    else if (entry.isFile()) { out.push(full); }
  }
  return out;
}

/** Path relative to src, forward-slashed, for stable messages. */
function rel(p: string): string {
  return relative(SRC, p).split(/[\\/]/).join('/');
}

function lineOf(text: string, index: number): number {
  return text.slice(0, index).split('\n').length;
}

// Blank out a region but keep its newlines, so reported line numbers survive the strip.
function blank(m: string): string {
  return m.replace(/[^\n]/g, ' ');
}

describe('architecture', () => {
  it('no component stylesheet @uses or @imports theme.scss — the Material theme stays out of per-component bundles', () => {
    // src/styles.scss is the one place the theme is applied (once), and theme.scss /
    // theme-tokens.scss are the theme files themselves. Everything else that compiles
    // into a component bundle may only take variables from theme-tokens.scss.
    const exempt = new Set(['styles.scss', 'theme.scss', 'theme-tokens.scss']);
    const violations: string[] = [];

    for (const file of walk(SRC).filter(p => p.endsWith('.scss'))) {
      if (exempt.has(rel(file))) { continue; }
      const text = readFileSync(file, 'utf8');
      const stmt = /@(?:use|import)\s+['"]([^'"]+)['"]/g;
      for (const m of text.matchAll(stmt)) {
        // The last path segment, sans .scss: '../../theme' and 'theme' both name theme.scss;
        // 'theme-tokens' must not match as a prefix.
        const name = m[1].split('/').pop()!.replace(/\.scss$/, '');
        if (name === 'theme') {
          violations.push(`${rel(file)}:${lineOf(text, m.index!)}  @${m[0].split(' ')[0].slice(1)} '${m[1]}'`);
        }
      }
    }

    expect(
      violations,
      [
        'A component stylesheet must not load the Material theme: it re-emits the whole theme into',
        "that component's bundle and against its 4 kB style budget. The theme is applied exactly once,",
        'in src/styles.scss. If this file needs values, @use the variables-only module instead:',
        "    @use '<path-to-src>/theme-tokens' as *;",
        'Offending statements:',
        ...violations,
      ].join('\n'),
    ).toEqual([]);
  });

  it('no component template uses the legacy structural control flow (*ngIf / *ngFor / *ngSwitch)', () => {
    // The app is on the built-in @if / @for / @switch syntax; a legacy *ngIf in a template
    // means the CommonModule dependency came back with it, and the two syntaxes do not
    // mix in one template. Spec files are excluded: their host components are test
    // fixtures, not app templates.
    const LEGACY = /\*ng(If|For|Switch)\b/g;

    // Blank out HTML comments and quoted strings before matching, so a *ngIf mentioned in
    // a comment or inside an attribute value cannot trip the rule.
    const strip = (src: string): string =>
      src
        .replace(/<!--[\s\S]*?-->/g, blank)
        .replace(/"(?:\\.|[^"\\\n])*"/g, blank)
        .replace(/'(?:\\.|[^'\\\n])*'/g, blank);

    const violations: string[] = [];
    const check = (label: string, src: string): void => {
      const clean = strip(src);
      for (const m of clean.matchAll(LEGACY)) {
        violations.push(`${label}:${lineOf(clean, m.index!)}  ${m[0]}`);
      }
    };

    for (const file of walk(APP).filter(p => p.endsWith('.html'))) {
      check(rel(file), readFileSync(file, 'utf8'));
    }
    for (const file of walk(APP).filter(p => p.endsWith('.ts') && !p.endsWith('.spec.ts'))) {
      const text = readFileSync(file, 'utf8');
      // Inline templates: the backtick string after `template:`. Escaped characters are
      // skipped so an escaped backtick inside the template does not end the capture early.
      const inline = /template:\s*`((?:\\.|[^`\\])*)`/g;
      for (const m of text.matchAll(inline)) {
        check(`${rel(file)} (inline template)`, m[1]);
      }
    }

    expect(
      violations,
      [
        'Legacy structural directives are gone from this app; the built-in control flow is the',
        'syntax and it does not mix with them in one template. Rewrite the above as:',
        '    @if (cond) { … } @else if (other) { … } @else { … }',
        '    @for (item of items; track item) { … }',
        "    @switch (value) { @case ('a') { … } @default { … } }",
        'and drop CommonModule if it was imported only for the directive.',
        'Offending usages:',
        ...violations,
      ].join('\n'),
    ).toEqual([]);
  });

  it('no component imports HttpClient directly — the store is the only fetcher', () => {
    // Components render store signals and trigger store methods; they never build params
    // and never call the API. The one HttpClient consumer is ApiService, which only the
    // store injects. A new direct import means a fetch path that bypasses the shared
    // filter contract and the busy/error bookkeeping — exactly the drift this test is for.
    // Spec files are excluded: HttpTestingController and provideHttpClientTesting are
    // the test harness, not app code.
    const HTTP_CLIENT = /\bHttpClient\b/g;
    const violations: string[] = [];

    // Comments and string/template literals are blanked first: several of them state this
    // very rule ("…never injects HttpClient"), and a mention is not an import.
    const strip = (src: string): string =>
      src
        .replace(/"(?:\\.|[^"\\\n])*"/g, blank)
        .replace(/'(?:\\.|[^'\\\n])*'/g, blank)
        .replace(/`(?:\\.|[^`\\])*`/g, blank)
        .replace(/\/\*[\s\S]*?\*\//g, blank)
        .replace(/\/\/[^\n]*/g, ' ');

    for (const file of walk(APP).filter(p => p.endsWith('.ts') && !p.endsWith('.spec.ts'))) {
      if (rel(file) === 'app/api/api.service.ts') { continue; }
      const clean = strip(readFileSync(file, 'utf8'));
      for (const m of clean.matchAll(HTTP_CLIENT)) {
        violations.push(`${rel(file)}:${lineOf(clean, m.index!)}`);
      }
    }

    expect(
      violations,
      [
        'The store is the only fetcher in this app: HttpClient may appear only in src/app/api/api.service.ts,',
        'and only the store may inject ApiService. A direct import in a component bypasses the shared',
        'filter contract, the busy counter and the error sentence. Route the request through the store',
        '(add the method there, render its signal here), or say deliberately why a second fetch path is needed.',
        'Offending files:',
        ...violations,
      ].join('\n'),
    ).toEqual([]);
  });
});
