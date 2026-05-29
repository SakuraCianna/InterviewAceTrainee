import { useEffect, useRef } from "react";
import type { EChartsOption } from "echarts";
import type { AdminDashboardStats, AdminStatsPoint } from "./types";

type AdminEChartsModule = typeof import("../../lib/adminEcharts");
type EChartsInstance = ReturnType<AdminEChartsModule["initAdminChart"]>;

const chartTextColor = "#334155";

export function lineDashboardOption(stats: AdminDashboardStats): EChartsOption {
  const labels = stats.daily_interviews.map((item) => item.label);
  return {
    color: ["#174ea6", "#b3261e", "#0b8043"],
    grid: { top: 36, right: 18, bottom: 28, left: 38 },
    legend: { top: 0, textStyle: { color: chartTextColor, fontWeight: 700 } },
    tooltip: { trigger: "axis" },
    xAxis: { type: "category", boundaryGap: false, data: labels, axisLabel: { color: chartTextColor } },
    yAxis: { type: "value", minInterval: 1, axisLabel: { color: chartTextColor }, splitLine: { lineStyle: { color: "rgba(15,23,42,0.08)" } } },
    series: [
      { name: "训练场次", type: "line", smooth: true, symbolSize: 8, lineStyle: { width: 3 }, areaStyle: { opacity: 0.1 }, data: stats.daily_interviews.map((item) => item.value) },
      { name: "生成报告", type: "line", smooth: true, symbolSize: 8, lineStyle: { width: 3 }, areaStyle: { opacity: 0.08 }, data: stats.daily_reports.map((item) => item.value) },
      { name: "新增用户", type: "line", smooth: true, symbolSize: 8, lineStyle: { width: 3 }, areaStyle: { opacity: 0.08 }, data: stats.user_growth.map((item) => item.value) },
    ],
  };
}

export function donutDashboardOption(points: AdminStatsPoint[], colors: string[]): EChartsOption {
  return {
    color: colors,
    tooltip: { trigger: "item" },
    legend: { bottom: 0, textStyle: { color: chartTextColor, fontWeight: 700 } },
    series: [
      {
        type: "pie",
        radius: ["48%", "72%"],
        center: ["50%", "42%"],
        avoidLabelOverlap: true,
        label: { formatter: "{b}\n{c}", color: "#0f172a", fontWeight: 900 },
        data: points.map((item) => ({ name: item.label, value: item.value })),
      },
    ],
  };
}

export function barDashboardOption(points: AdminStatsPoint[], name: string, color: string): EChartsOption {
  return {
    color: [color],
    grid: { top: 16, right: 18, bottom: 28, left: 38 },
    tooltip: { trigger: "axis" },
    xAxis: { type: "category", data: points.map((item) => item.label), axisLabel: { color: chartTextColor } },
    yAxis: { type: "value", minInterval: 1, axisLabel: { color: chartTextColor }, splitLine: { lineStyle: { color: "rgba(15,23,42,0.08)" } } },
    series: [
      {
        name,
        type: "bar",
        barMaxWidth: 42,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        data: points.map((item) => item.value),
      },
    ],
  };
}

export function AdminChart({ option, height = 280 }: { option: EChartsOption; height?: number }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<EChartsInstance | null>(null);
  const optionRef = useRef(option);

  useEffect(() => {
    optionRef.current = option;
    chartRef.current?.setOption(option, true);
  }, [option]);

  useEffect(() => {
    let disposed = false;
    let resizeObserver: ResizeObserver | null = null;

    void import("../../lib/adminEcharts")
      .then(({ initAdminChart }) => {
        if (disposed || !containerRef.current) {
          return;
        }
        chartRef.current = initAdminChart(containerRef.current);
        chartRef.current.setOption(optionRef.current);
        if ("ResizeObserver" in window) {
          resizeObserver = new ResizeObserver(() => chartRef.current?.resize());
          resizeObserver.observe(containerRef.current);
        }
      })
      .catch(() => undefined);

    return () => {
      disposed = true;
      resizeObserver?.disconnect();
      chartRef.current?.dispose();
      chartRef.current = null;
    };
  }, []);

  return <div ref={containerRef} className="admin-chart" style={{ height }} />;
}
