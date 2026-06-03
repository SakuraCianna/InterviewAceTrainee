import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { RouteMotion } from "./components/RouteMotion";

const AdminShell = lazy(() => import("./pages/admin/AdminShell").then((module) => ({ default: module.AdminShell })));
const AuthPage = lazy(() => import("./pages/AuthPage").then((module) => ({ default: module.AuthPage })));
const HomePage = lazy(() => import("./pages/HomePage").then((module) => ({ default: module.HomePage })));
const InterviewRoom = lazy(() => import("./pages/interview/InterviewRoom").then((module) => ({ default: module.InterviewRoom })));
const LegalPage = lazy(() => import("./pages/LegalPage").then((module) => ({ default: module.LegalPage })));

const adminEntryPath = import.meta.env.VITE_ADMIN_ENTRY_PATH || "/sakuracianna";

export default function App() {
  return (
    <RouteMotion>
      <Suspense
        fallback={
          <main className="route-loading">
            <div className="route-loading-mark" role="status" aria-live="polite">
              <span aria-hidden="true" />
              <p>正在加载训练界面</p>
            </div>
          </main>
        }
      >
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/login" element={<AuthPage mode="login" />} />
          <Route path="/register" element={<AuthPage mode="register" />} />
          <Route path="/interview" element={<InterviewRoom />} />
          <Route path="/interview/check" element={<InterviewRoom />} />
          <Route path="/interview/room" element={<InterviewRoom />} />
          <Route path={adminEntryPath} element={<AdminShell />} />
          <Route path="/terms" element={<LegalPage type="terms" />} />
          <Route path="/privacy" element={<LegalPage type="privacy" />} />
          <Route path="/refund" element={<LegalPage type="refund" />} />
          <Route path="/contact" element={<LegalPage type="contact" />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </RouteMotion>
  );
}
