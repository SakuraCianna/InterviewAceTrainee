import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    clearMocks: true,
    restoreMocks: true,
  },
  build: {
    chunkSizeWarningLimit: 650,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) {
            return;
          }
          if (id.includes("echarts") || id.includes("zrender")) {
            return "vendor-echarts";
          }
          if (id.includes("antd-mobile")) {
            return "vendor-antd";
          }
          if (id.includes("gsap")) {
            return "vendor-gsap";
          }
          if (id.includes("@iconify")) {
            return "vendor-icons";
          }
          if (id.includes("react") || id.includes("react-dom") || id.includes("react-router-dom")) {
            return "vendor-react";
          }
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8000",
        ws: true
      }
    }
  }
});
