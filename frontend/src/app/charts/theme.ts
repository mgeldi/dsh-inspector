import type { EChartsCoreOption } from 'echarts/core';
import type { Plane } from '../api/types';

// src/theme.scss is the source of truth for the look. SCSS cannot be imported into
// TypeScript, so the tokens the charts need are mirrored here, by value — keep the two
// places in sync, and no component may hardcode a hex of its own.
export const PLANE_COLOURS: Record<Plane, string> = {
  GUARD: '#e0a458',           // $plane-guard
  MODEL_MISUSE: '#e05c5c',    // $plane-misuse
  INFRASTRUCTURE: '#5b8fd6',  // $plane-infra
};

const TEXT_LO = '#99a1b3';    // $text-lo
const HAIRLINE = '#262b36';   // $hairline

/** The plane palette in the fixed order Guard, Model misuse, Infrastructure. */
export const PLANE_PALETTE: readonly string[] = [
  PLANE_COLOURS.GUARD,
  PLANE_COLOURS.MODEL_MISUSE,
  PLANE_COLOURS.INFRASTRUCTURE,
];

/** One chart theme for the app: dark surface, grid padding, hairline axes. */
export const CHART_BASE: EChartsCoreOption = {
  backgroundColor: 'transparent',
  textStyle: { color: TEXT_LO },
  grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
  legend: { textStyle: { color: TEXT_LO } },
  tooltip: { trigger: 'axis' },
  xAxis: {
    axisLine: { lineStyle: { color: HAIRLINE } },
    axisTick: { show: false },
    axisLabel: { color: TEXT_LO },
  },
  yAxis: {
    axisLine: { show: false },
    splitLine: { lineStyle: { color: HAIRLINE } },
    axisLabel: { color: TEXT_LO },
  },
};
