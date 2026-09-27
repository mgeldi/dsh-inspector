import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import type { FindingsPageDto, OverviewDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { FilterRail } from './filter-rail';
import { ViewUrl } from '../state/view-url';
import { FakeViewUrl } from '../state/view-url.testing';
import { applyPreset } from '../state/filters';

// Invented test data: the vocabulary mixes multi-valued facets, one single-valued
// facet and `unknown` as an ordinary value. Nothing is from any real corpus.
const overview: OverviewDto = {
  tiles: { sessions: 12, findings: 9, toolCalls: 32, steps: 24 },
  planeMix: {},
  topDetectors: [], topCodes: [], uncodedFindings: 0,
  series: [],
  throughput: [],
  vocabulary: {
    schemas: ['V0', 'V3'],
    models: ['demo-flash-8b'],
    providers: ['demo-gateway', 'demo-gateway-impl'],
    roles: ['orchestrator', 'subagent', 'unknown'],
    presets: ['unknown', 'smoke'],
    harnessVersions: ['0.1.0', '0.2.0'],
    codes: [], detectors: [],
  },
};
const emptyFindings: FindingsPageDto = { total: 0, page: 0, size: 20, items: [] };

describe('FilterRail', () => {
  let store: InsightsStore;
  let http: HttpTestingController;
  let fixture: ComponentFixture<FilterRail>;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FilterRail],
      providers: [
        provideHttpClient(withFetch()),
        provideHttpClientTesting(),
        // The controls navigate now; this closes the same loop the shell closes, so these
        // specs keep asserting what a user gets rather than only that a URL was requested.
        { provide: ViewUrl, useFactory: () => new FakeViewUrl(TestBed.inject(InsightsStore)) },
      ],
    }).compileComponents();

    store = TestBed.inject(InsightsStore);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FilterRail);
    el = fixture.nativeElement as HTMLElement;

    // The rail reads store.vocabulary(); seed it through one completed load.
    store.loadOverview();
    http.expectOne(r => r.url === '/api/overview').flush(overview);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  function select(facet: string): HTMLSelectElement {
    return el.querySelector(`#facet-${facet}`) as HTMLSelectElement;
  }

  it('renders one select per facet, each populated from the store vocabulary', () => {
    expect(Array.from(el.querySelectorAll('select')).map(s => s.id)).toEqual([
      'facet-schema', 'facet-model', 'facet-provider', 'facet-role', 'facet-preset', 'facet-harnessVersion',
    ]);
    expect(Array.from(select('schema').options).map(o => o.value)).toEqual(['', 'V0', 'V3']);
  });

  it('renders a single-valued facet disabled with its value shown, not hidden', () => {
    const model = select('model');
    expect(model, 'model facet exists').toBeTruthy();
    expect(model.disabled).toBe(true);
    expect(model.value, 'the single value is shown').toBe('demo-flash-8b');
    expect(model.options.item(0)?.disabled, '(any) is not selectable on a fixed facet').toBe(true);

    expect(select('schema').disabled, 'multi-valued facets stay enabled').toBe(false);
  });

  it('dims the facet the cohorts page groups by, and says why, without hiding it', () => {
    // The grouping axis is the one facet a cohort table cannot meaningfully be filtered by —
    // every row would carry the same value. The control stays visible with its option list
    // intact and is disabled with an explanation, because a rail that silently disagrees with
    // the page beside it is worse than a control that admits it is inert.
    store.cohortAxis.set('harnessVersion');
    fixture.detectChanges();

    const axis = select('harnessVersion');
    expect(axis.disabled, 'the axis is not filterable while it is the axis').toBe(true);
    expect(axis.classList, 'dimmed by class, not removed from the DOM').toContain('facet-inert');
    expect(el.querySelector('.facet-note')?.textContent).toContain('grouped by this');

    const other = select('schema');
    expect(other.disabled, 'the other facets keep working').toBe(false);
    expect(other.classList).not.toContain('facet-inert');

    expect(axis.options.item(0)?.disabled, 'inert is not the same as single-valued').toBe(false);

    store.cohortAxis.set(null);
    fixture.detectChanges();
    expect(select('harnessVersion').disabled, 'leaving cohorts re-arms the facet').toBe(false);
  });

  it('keeps unknown selectable, with the label unknown verbatim', () => {
    const preset = select('preset');
    expect(preset.disabled).toBe(false);

    const unknown = Array.from(preset.options).find(o => o.value === 'unknown');
    expect(unknown, "the 'unknown' option").toBeDefined();
    expect(unknown!.disabled).toBe(false);
    expect(unknown!.textContent, 'the label is the value, verbatim').toBe('unknown');
  });

  it('writes the facet through the store and reloads when a select changes', () => {
    const loadAll = vi.spyOn(store, 'loadAll');

    const schema = select('schema');
    schema.value = 'V3';
    schema.dispatchEvent(new Event('change'));

    expect(store.filters().schema, 'the store owns the write').toBe('V3');
    expect(loadAll).toHaveBeenCalledTimes(1);

    const overviewReq = http.expectOne(r => r.url === '/api/overview');
    expect(overviewReq.request.params.get('schema')).toBe('V3');
    overviewReq.flush(overview);
    const findingsReq = http.expectOne(r => r.url === '/api/findings');
    expect(findingsReq.request.params.get('schema')).toBe('V3');
    findingsReq.flush(emptyFindings);
  });

  it('writes null — not an empty string — when (any) is chosen', () => {
    store.filters.update(f => ({ ...f, ...{ schema: 'V0' } }));
    fixture.detectChanges();
    expect(select('schema').value).toBe('V0');

    const schema = select('schema');
    schema.value = '';
    schema.dispatchEvent(new Event('change'));
    // The rail writes null, which removes the key; read back from the URL the facet is absent.
    const fake = TestBed.inject(ViewUrl) as unknown as FakeViewUrl;
    expect(fake.last()?.filters?.schema).toBeNull();
    expect(store.filters().schema ?? null).toBeNull();

    const req = http.expectOne(r => r.url === '/api/overview');
    expect(req.request.params.has('schema'), 'absent, never the empty string').toBe(false);
    req.flush(overview);
    http.expectOne(r => r.url === '/api/findings').flush(emptyFindings);
  });

  it('switches the time preset on its chip and reloads', () => {
    const loadAll = vi.spyOn(store, 'loadAll');
    const chips = Array.from(el.querySelectorAll('.chip'));
    expect(chips.map(c => c.textContent?.trim())).toEqual(['24 h', '7 d', '30 d', 'All time']);
    expect(chips[3].classList, 'the default preset is all time').toContain('chip-active');

    (chips[1] as HTMLButtonElement).click();
    expect(store.preset()).toBe('7d');
    expect(store.filters().from, '24h-style presets pin the window').not.toBeNull();
    expect(store.filters().to).not.toBeNull();
    expect(loadAll).toHaveBeenCalledTimes(1);

    http.expectOne(r => r.url === '/api/overview').flush(overview);
    http.expectOne(r => r.url === '/api/findings').flush(emptyFindings);
  });

  it('clears every facet and the preset on Clear, and reloads', () => {
    store.filters.update(f => ({ ...f, ...{ schema: 'V0' } }));
    store.filters.update(f => applyPreset(f, '7d', 1_790_000_000_000));
    // Rendered before the click, as a user would see it: Clear is disabled on an unfiltered
    // view, and this spec used to pass only because it never was.
    fixture.detectChanges();
    const loadAll = vi.spyOn(store, 'loadAll');

    (el.querySelector('.clear') as HTMLButtonElement).click();
    expect(store.filters().presetId).toBe('all');
    expect(store.filters().from ?? null, 'the range is open again').toBeNull();
    expect(store.filters().schema ?? null, 'and no facet is left').toBeNull();
    expect(loadAll).toHaveBeenCalledTimes(1);

    http.expectOne(r => r.url === '/api/overview').flush(overview);
    http.expectOne(r => r.url === '/api/findings').flush(emptyFindings);
  });

  /**
   * Provider and role split one model id into the populations it actually serves — the
   * orchestrator and its subagents. They are ordinary facets: a select from the vocabulary,
   * written through the URL, sent on every shared read.
   */
  it('filters by provider and role like any other facet', () => {
    expect(Array.from(select('provider').options).map(o => o.value))
      .toEqual(['', 'demo-gateway', 'demo-gateway-impl']);

    const role = select('role');
    expect(Array.from(role.options).map(o => o.value)).toEqual(['', 'orchestrator', 'subagent', 'unknown']);
    role.value = 'subagent';
    role.dispatchEvent(new Event('change'));
    expect(store.filters().role).toBe('subagent');

    const overviewReq = http.expectOne(r => r.url === '/api/overview');
    expect(overviewReq.request.params.get('role')).toBe('subagent');
    overviewReq.flush(overview);
    const findingsReq = http.expectOne(r => r.url === '/api/findings');
    expect(findingsReq.request.params.get('role')).toBe('subagent');
    findingsReq.flush(emptyFindings);
  });

  it('dims the provider or role facet while the cohorts page groups by it', () => {
    for (const axis of ['provider', 'role']) {
      store.cohortAxis.set(axis);
      fixture.detectChanges();
      expect(select(axis).disabled, `${axis} is inert as the axis`).toBe(true);
      expect(select(axis).classList).toContain('facet-inert');
    }
    expect(select('provider').disabled, 'and re-armed once the axis moves on').toBe(false);
  });

  /**
   * An untouched facet is absent (undefined) and a cleared one is null; both mean "not
   * filtered". A strict `!== null` read every absent facet as active, so Clear was never
   * disabled and could not answer "am I looking at everything?" — the one thing it is for.
   */
  it('disables Clear when nothing is filtered, whether facets are absent or cleared', () => {
    const clear = () => el.querySelector('.clear') as HTMLButtonElement;
    expect(store.filters()).toEqual({ presetId: 'all' });
    expect(clear().disabled, 'fresh filters: absent facets').toBe(true);

    // The shape the URL produces: an open range spelled as nulls, a facet cleared to null.
    store.filters.set({ presetId: 'all', from: null, to: null, role: null });
    fixture.detectChanges();
    expect(clear().disabled, 'cleared facets and an open range').toBe(true);

    store.filters.set({ presetId: 'all', from: null, to: null, provider: 'demo-gateway' });
    fixture.detectChanges();
    expect(clear().disabled, 'one facet set').toBe(false);
  });

  /**
   * A filter set before the axis was chosen stays in the URL for the other screens, but the
   * cohort requests leave it out. A disabled select still showing a value would read as a
   * filter in force, so the note says it is not applied here.
   */
  it('says a filter on the grouping axis is not applied on the cohorts screen', () => {
    store.cohortAxis.set('harnessVersion');
    store.filters.update(f => ({ ...f, harnessVersion: '0.2.0' }));
    fixture.detectChanges();

    expect(select('harnessVersion').value, 'the value stays visible').toBe('0.2.0');
    expect(el.querySelector('.facet-note')?.textContent).toContain('not applied here');
    expect(el.querySelector('.facet-note')?.textContent).toContain('Overview and Findings');
  });

  /**
   * The badge and Clear read one count. A drill-down narrows the Findings screen alone: there it
   * counts and Clear clears it; elsewhere it narrows nothing, so neither the badge nor Clear
   * claims it. The previous spec set a facet beside the drill-down and so never saw Clear stay
   * disabled under a badge reading "1 active".
   */
  it('enables Clear for a drill-down exactly where the badge counts it, and clears it', () => {
    const clear = () => el.querySelector('.clear') as HTMLButtonElement;
    store.detector.set('edit-miss');
    fixture.detectChanges();
    expect(store.activeFilterCount(), 'on Overview it narrows nothing').toBe(0);
    expect(clear().disabled).toBe(true);

    store.findingsOpen.set(true);
    fixture.detectChanges();
    expect(store.activeFilterCount(), 'the badge counts it on Findings').toBe(1);
    expect(clear().disabled, 'and Clear agrees').toBe(false);

    clear().click();
    expect(store.detector()).toBeNull();
    // the population did not change, so only the table re-asks — as the shell would have it
    expect(http.match(r => r.url === '/api/overview')).toHaveLength(0);
    const req = http.expectOne(r => r.url === '/api/findings');
    expect(req.request.params.has('detector')).toBe(false);
    req.flush(emptyFindings);
  });

  /**
   * The rail is re-created each time it is opened. Its selects bound `[value]`, which is applied
   * before the options exist, so a rail opened on a filtered view showed "(any)" for a filter in
   * force (the vocabulary already loaded, so nothing re-applied the value).
   */
  it('shows the active value when it is opened on a filtered view', () => {
    store.filters.update(f => ({ ...f, provider: 'demo-gateway-impl' }));
    const reopened = TestBed.createComponent(FilterRail);
    reopened.detectChanges();
    const provider = (reopened.nativeElement as HTMLElement).querySelector('#facet-provider') as HTMLSelectElement;
    expect(provider.value).toBe('demo-gateway-impl');
  });
});
