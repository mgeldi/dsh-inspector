import {
  ChangeDetectionStrategy, Component, ElementRef, Input, OnChanges, OnDestroy, ViewChild, afterNextRender,
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
export class ChartComponent implements OnChanges, OnDestroy {
  @Input() option: echarts.EChartsCoreOption | null = null;
  @ViewChild('host', { static: true }) host!: ElementRef<HTMLElement>;
  private chart?: echarts.ECharts;
  private readonly resizeObs = new ResizeObserver(() => this.chart?.resize());

  constructor() {
    afterNextRender(() => {
      this.chart = echarts.init(this.host.nativeElement, undefined, { renderer: 'canvas' });
      this.resizeObs.observe(this.host.nativeElement);
      if (this.option) { this.chart.setOption(this.option, true); }
    });
  }

  // setOption(option, true) replaces rather than merges: a filtered-out series must not
  // ghost over the new data.
  ngOnChanges(): void { if (this.chart && this.option) { this.chart.setOption(this.option, true); } }

  ngOnDestroy(): void { this.resizeObs.disconnect(); this.chart?.dispose(); }
}
