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

/**
 * The axis and label tokens a chart option needs, by value — the same keep-in-sync
 * contract as the rest of this file, so no component hardcodes a hex of its own.
 */
export const CHART_AXIS = {
  hairline: HAIRLINE,
  textLo: TEXT_LO,
} as const;

/**
 * The daily findings volume bar: the accent at rest, at a level that reads as activity
 * without competing with the plane hues next to it. A day's findings are not one plane,
 * so painting the bar with a plane colour would argue with the plane mix; the accent says
 * "this app", the plane colours say "which kind of problem".
 */
export const DAILY_BAR_COLOUR = 'rgba(91, 214, 165, 0.55)'; // $accent at rest
export const DAILY_BAR_EMPHASIS = '#5bd6a5';                // $accent, hovered

/** One chart theme for the app: dark surface, grid padding, hairline axes. */
export const CHART_BASE: EChartsCoreOption = {
  backgroundColor: 'transparent',
  textStyle: { color: TEXT_LO },
  grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
  legend: { textStyle: { color: TEXT_LO } },
  // ECharts' default tooltip is a white box with black text: on a dark dashboard it is the
  // one element that announces itself as somebody else's component. Text HI is the mirrored
  // $text-hi, since the tooltip is drawn outside the component tree and cannot see the CSS.
  tooltip: {
    trigger: 'axis',
    backgroundColor: '#191c24', // $surface-raised
    borderColor: HAIRLINE,
    borderWidth: 1,
    padding: [7, 10],
    textStyle: { color: '#e8ebf2', fontSize: 12 },
    extraCssText: 'border-radius: 8px; box-shadow: 0 10px 28px rgba(0, 0, 0, 0.5);',
    axisPointer: {
      type: 'shadow',
      shadowStyle: { color: 'rgba(232, 235, 242, 0.05)' },
    },
  },
  // A short settle on load; the default 1 s ease-out bounce reads as decoration on a
  // measurement screen, and re-renders on every filter change would replay it.
  animationDuration: 320,
  animationEasing: 'cubicOut',
  animationDurationUpdate: 220,
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
