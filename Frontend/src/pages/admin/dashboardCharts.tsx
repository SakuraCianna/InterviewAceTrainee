import { useEffect, useRef } from "react";
import type { EChartsOption } from "echarts";
import type { AdminDashboardStats, AdminStatsPoint } from "./types";
import { adminClasses } from "./adminStyles";

type AdminEChartsModule = typeof import("../../lib/adminEcharts");
type EChartsInstance = ReturnType<AdminEChartsModule["initAdminChart"]>;

const chartTextColor = "#444746";
const chartMutedColor = "#747775";

const labelTranslations: Record<string, string> = {
  job: "求职面试",
  postgraduate: "考研复试",
  civil_service: "考公面试",
  ielts: "雅思口语",
  active: "进行中",
  deleted: "已删除",
  completed: "已完成",
  created: "已创建",
  awaiting_ai: "等待AI",
  open: "待处理",
  processing: "处理中",
  resolved: "已解决",
  rejected: "已驳回",
  general: "常规",
  refund_request: "退款",
  service_dispute: "争议",
  service_compensation: "补偿",
};

export function translateChartLabel(label: string): string {
  return labelTranslations[label] ?? label;
}

export function lineDashboardOption(stats: AdminDashboardStats): EChartsOption {
  const labels = stats.daily_interviews.map((item) => item.label);
  return {
    color: ["#0b57d0", "#b3261e", "#146c2e"],
    grid: { top: 36, right: 20, bottom: 28, left: 38 },
    legend: { top: 0, icon: "circle", textStyle: { color: chartTextColor, fontWeight: 600 } },
    tooltip: {
      trigger: "axis",
      backgroundColor: "#ffffff",
      borderColor: "#e1e3e1",
      borderRadius: 12,
      padding: [10, 14],
      textStyle: { color: "#1f1f1f", fontSize: 13 },
      extraCssText: "box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);",
    },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: labels,
      axisLabel: { color: chartMutedColor, fontSize: 12 },
      axisLine: { lineStyle: { color: "#e1e3e1" } },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
      axisLabel: { color: chartMutedColor, fontSize: 12 },
      splitLine: { lineStyle: { color: "rgba(0, 0, 0, 0.05)", type: "dashed" } },
    },
    series: [
      { name: "训练场次", type: "line", smooth: 0.35, symbolSize: 7, lineStyle: { width: 3 }, areaStyle: { opacity: 0.12 }, data: stats.daily_interviews.map((item) => item.value) },
      { name: "生成报告", type: "line", smooth: 0.35, symbolSize: 7, lineStyle: { width: 3 }, areaStyle: { opacity: 0.08 }, data: stats.daily_reports.map((item) => item.value) },
      { name: "新增用户", type: "line", smooth: 0.35, symbolSize: 7, lineStyle: { width: 3 }, areaStyle: { opacity: 0.08 }, data: stats.user_growth.map((item) => item.value) },
    ],
  };
}

export function donutDashboardOption(points: AdminStatsPoint[], colors: string[]): EChartsOption {
  return {
    color: colors,
    tooltip: {
      trigger: "item",
      backgroundColor: "#ffffff",
      borderColor: "#e1e3e1",
      borderRadius: 12,
      padding: [8, 12],
      textStyle: { color: "#1f1f1f" },
      extraCssText: "box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);",
    },
    legend: { bottom: 0, icon: "circle", textStyle: { color: chartTextColor, fontWeight: 600 } },
    series: [
      {
        type: "pie",
        radius: ["50%", "74%"],
        center: ["50%", "44%"],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: "#ffffff", borderWidth: 2 },
        label: { formatter: "{b}\n{c}", color: "#1f1f1f", fontWeight: 700, fontSize: 13 },
        data: points.map((item) => ({ name: translateChartLabel(item.label), value: item.value })),
      },
    ],
  };
}

export function barDashboardOption(points: AdminStatsPoint[], name: string, color: string): EChartsOption {
  return {
    color: [color],
    grid: { top: 20, right: 20, bottom: 28, left: 38 },
    tooltip: {
      trigger: "axis",
      backgroundColor: "#ffffff",
      borderColor: "#e1e3e1",
      borderRadius: 12,
      padding: [8, 12],
      textStyle: { color: "#1f1f1f" },
      extraCssText: "box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);",
    },
    xAxis: {
      type: "category",
      data: points.map((item) => translateChartLabel(item.label)),
      axisLabel: { color: chartMutedColor, fontSize: 12 },
      axisLine: { lineStyle: { color: "#e1e3e1" } },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
      axisLabel: { color: chartMutedColor, fontSize: 12 },
      splitLine: { lineStyle: { color: "rgba(0, 0, 0, 0.05)", type: "dashed" } },
    },
    series: [
      {
        name,
        type: "bar",
        barMaxWidth: 36,
        itemStyle: { borderRadius: [8, 8, 0, 0] },
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

  return <div ref={containerRef} className={adminClasses("admin-chart")} style={{ height }} />;
}
