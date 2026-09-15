import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import type { EChartsCoreOption } from 'echarts/core';
import { describe, expect, it, vi } from 'vitest';
import { ChartComponent } from './chart';

// jsdom has no ResizeObserver; the wrapper only needs the API surface, and the call log
// is what the destroy test asserts on.
const observerCalls: string[] = [];
class FakeResizeObserver {
  observe(): void { observerCalls.push('observe'); }
  unobserve(): void { /* nothing to observe in jsdom */ }
  disconnect(): void { observerCalls.push('disconnect'); }
}
if (typeof globalThis.ResizeObserver === 'undefined') {
  (globalThis as { ResizeObserver?: unknown }).ResizeObserver = FakeResizeObserver;
}

// Mock the charting library at its four entry points. The spec is about the wrapper's
// contract — setOption with the replace flag, resize observation, dispose on destroy —
// not about ECharts' internals, and jsdom cannot host a real canvas.
interface FakeInstance {
  setOptionCalls: Array<{ option: unknown; replace?: boolean }>;
  resizeCount: number;
  disposeCount: number;
  setOption(option: unknown, replace?: boolean): void;
  resize(): void;
  dispose(): void;
}
const echartsMocks = vi.hoisted(() => {
  const instances: FakeInstance[] = [];
  const useCalls: Array<unknown[]> = [];
  const use = (...args: unknown[]): void => { useCalls.push(args); };
  const init = (): FakeInstance => {
    const instance: FakeInstance = {
      setOptionCalls: [],
      resizeCount: 0,
      disposeCount: 0,
      setOption(option: unknown, replace?: boolean): void { instance.setOptionCalls.push({ option, replace }); },
      resize(): void { instance.resizeCount += 1; },
      dispose(): void { instance.disposeCount += 1; },
    };
    instances.push(instance);
    return instance;
  };
  return { instances, useCalls, use, init };
});

vi.mock('echarts/core', () => echartsMocks);
vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {} }));
vi.mock('echarts/components', () => ({ GridComponent: {}, LegendComponent: {}, TooltipComponent: {} }));
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }));

@Component({
  standalone: true,
  imports: [ChartComponent],
  template: '<app-chart [option]="option()"></app-chart>',
})
class TestHost {
  readonly option = input<EChartsCoreOption | null>(null);
}

describe('ChartComponent', () => {
  it('registers the tree-shaken set and calls setOption with the replace flag when the option input is set', async () => {
    // use is called once with the tree-shaken set: two charts, three components, the canvas renderer.
    expect(echartsMocks.useCalls).toHaveLength(1);
    expect(echartsMocks.useCalls[0]).toHaveLength(1);
    expect(echartsMocks.useCalls[0][0]).toHaveLength(6);

    const fixture = TestBed.createComponent(TestHost);
    fixture.componentRef.setInput('option', { xAxis: { type: 'category' }, series: [] });
    fixture.detectChanges();
    await fixture.whenStable();

    const instance = echartsMocks.instances.at(-1);
    expect(instance).toBeDefined();
    expect(instance!.setOptionCalls.length).toBeGreaterThan(0);
    expect(instance!.setOptionCalls.at(-1)!.replace).toBe(true);
    expect((instance!.setOptionCalls.at(-1)!.option as { series?: unknown }).series).toEqual([]);
  });

  it('observes the host element so a dragged window resizes the chart', async () => {
    const observedBefore = observerCalls.filter(c => c === 'observe').length;
    const fixture = TestBed.createComponent(TestHost);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(observerCalls.filter(c => c === 'observe').length).toBe(observedBefore + 1);
  });

  it('disposes the chart and stops observing when the component is destroyed', async () => {
    const fixture = TestBed.createComponent(TestHost);
    fixture.detectChanges();
    await fixture.whenStable();
    const instance = echartsMocks.instances.at(-1);
    expect(instance!.disposeCount).toBe(0);

    fixture.destroy();
    expect(instance!.disposeCount).toBe(1);
    expect(observerCalls).toContain('disconnect');
  });
});
