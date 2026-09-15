import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, effect, inject, input, viewChild, afterNextRender,
} from '@angular/core';
import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

// Tree-shaken: only the two charts and the three components the dashboard uses, on the
// canvas renderer. Importing the full `echarts` package would double the bundle for no
// reason.
echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

/**
 * The only place that touches a charting library. Two jobs beyond rendering: dispose on
 * destroy, because a route that swaps components leaks a canvas per visit; and resize,
 * because the dashboard is read in a window that gets dragged.
 */
@Component({
  selector: 'app-chart', standalone: true, changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div #host class="chart"></div>`,
  styles: [`:host,.chart,.chart>div{height:100%;width:100%} :host{display:block;min-height:220px}`],
})
export class ChartComponent {
  readonly option = input<echarts.EChartsCoreOption | null>(null);
  readonly host = viewChild.required<ElementRef<HTMLElement>>('host');
  private chart?: echarts.ECharts;
  private readonly resizeObs = new ResizeObserver(() => this.chart?.resize());

  constructor() {
    afterNextRender(() => {
      const el = this.host().nativeElement;
      this.chart = echarts.init(el, undefined, { renderer: 'canvas' });
      this.resizeObs.observe(el);
      // The input may already carry a value by the first render; apply it once the canvas
      // exists, so nothing depends on whether the effect beat the render callback.
      const option = this.option();
      if (option) { this.chart.setOption(option, true); }
    });

    // setOption(option, true) replaces rather than merges: a filtered-out series must not
    // ghost over the new data. Skips the first tick when the canvas does not exist yet —
    // the render callback above owns that case.
    effect(() => {
      const option = this.option();
      if (this.chart && option) { this.chart.setOption(option, true); }
    });

    inject(DestroyRef).onDestroy(() => {
      this.resizeObs.disconnect();
      this.chart?.dispose();
    });
  }
}
