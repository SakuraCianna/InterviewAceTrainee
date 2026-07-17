import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { productConfig } from "./config/productConfig";
import { CSRF_COOKIE_NAME, getCookie } from "./lib/api";

function RequireAuth({ children }: { children: React.ReactNode }) {
  if (!getCookie(CSRF_COOKIE_NAME)) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

const AdminShell = lazy(() => import("./pages/admin/AdminShell").then((module) => ({ default: module.AdminShell })));
const AuthPage = lazy(() => import("./pages/AuthPage").then((module) => ({ default: module.AuthPage })));
const HomePage = lazy(() => import("./pages/HomePage").then((module) => ({ default: module.HomePage })));
const InterviewRoom = lazy(() => import("./pages/interview/InterviewRoom").then((module) => ({ default: module.InterviewRoom })));
const LegalPage = lazy(() => import("./pages/LegalPage").then((module) => ({ default: module.LegalPage })));

export default function App() {
  return (
    <Suspense fallback={<main className="route-loading">页面加载中...</main>}>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/login" element={<AuthPage mode="login" />} />
        <Route path="/register" element={<AuthPage mode="register" />} />
        <Route path="/interview" element={<RequireAuth><InterviewRoom /></RequireAuth>} />
        <Route path="/interview/check" element={<RequireAuth><InterviewRoom /></RequireAuth>} />
        <Route path="/interview/room" element={<RequireAuth><InterviewRoom /></RequireAuth>} />
        <Route path={productConfig.adminEntryPath} element={<AdminShell />} />
        <Route path="/terms" element={<LegalPage type="terms" />} />
        <Route path="/privacy" element={<LegalPage type="privacy" />} />
        <Route path="/refund" element={<LegalPage type="refund" />} />
        <Route path="/contact" element={<LegalPage type="contact" />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Suspense>
  );
}
