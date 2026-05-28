import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { unstableSetRender } from "antd-mobile";
import App from "./App";
import "antd-mobile/es/global/global.css";
import "antd-mobile/es/components/auto-center/auto-center.css";
import "antd-mobile/es/components/button/button.css";
import "antd-mobile/es/components/dot-loading/dot-loading.css";
import "antd-mobile/es/components/grid/grid.css";
import "antd-mobile/es/components/mask/mask.css";
import "antd-mobile/es/components/safe-area/safe-area.css";
import "antd-mobile/es/components/selector/selector.css";
import "antd-mobile/es/components/space/space.css";
import "antd-mobile/es/components/spin-loading/spin-loading.css";
import "antd-mobile/es/components/tabs/tabs.css";
import "antd-mobile/es/components/toast/toast.css";
import "./styles/index.css";

unstableSetRender((node, container) => {
  const root = createRoot(container);
  root.render(node);
  return async () => {
    root.unmount();
  };
});

createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>
);
