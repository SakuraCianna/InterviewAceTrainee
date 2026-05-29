import { BarChart, LineChart, PieChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import * as echarts from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";

let registered = false;

function registerAdminCharts() {
  if (registered) {
    return;
  }
  echarts.use([LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);
  registered = true;
}

export function initAdminChart(container: HTMLDivElement) {
  registerAdminCharts();
  return echarts.init(container);
}
