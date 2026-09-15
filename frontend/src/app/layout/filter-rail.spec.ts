import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import type { FindingsPageDto, OverviewDto } from '../api/types';
import { InsightsStore } from '../state/insights.store';
import { FilterRail } from './filter-rail';

// Invented test data: the vocabulary mixes multi-valued facets, one single-valued
// facet and `unknown` as an ordinary value. Nothing is from any real corpus.
const overview: OverviewDto = {
  tiles: { sessions: 12, findings: 9, toolCalls: 32, steps: 24 },
  planeMix: {},
  topDetectors: [],
  series: [],
  throughput: [],
  vocabulary: {
    schemas: ['V0', 'V3'],
    models: ['demo-flash-8b'],
    presets: ['unknown', 'smoke'],
    harnessVersions: ['0.1.0', '0.2.0'],
    codes: [], detectors: [], sessions: [],
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
      providers: [provideHttpClient(withFetch()), provideHttpClientTesting()],
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
      'facet-schema', 'facet-model', 'facet-preset', 'facet-harnessVersion',
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
    store.setFilters({ schema: 'V0' });
    fixture.detectChanges();
    expect(select('schema').value).toBe('V0');

    const schema = select('schema');
    schema.value = '';
    schema.dispatchEvent(new Event('change'));
    expect(store.filters().schema).toBeNull();

    http.expectOne(r => r.url === '/api/overview').flush(overview);
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
    store.setFilters({ schema: 'V0' });
    store.setPreset('7d', 1_790_000_000_000);
    const loadAll = vi.spyOn(store, 'loadAll');

    (el.querySelector('.clear') as HTMLButtonElement).click();
    expect(store.filters()).toEqual({ presetId: 'all' });
    expect(loadAll).toHaveBeenCalledTimes(1);

    http.expectOne(r => r.url === '/api/overview').flush(overview);
    http.expectOne(r => r.url === '/api/findings').flush(emptyFindings);
  });
});
