import { Navigate, Route, Routes } from "react-router-dom";
import { AdminShell } from "./pages/admin/AdminShell";
import { AuthPage } from "./pages/AuthPage";
import { HomePage } from "./pages/HomePage";
import { InterviewRoom } from "./pages/interview/InterviewRoom";
import { LegalPage } from "./pages/LegalPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/login" element={<AuthPage mode="login" />} />
      <Route path="/register" element={<AuthPage mode="register" />} />
      <Route path="/interview" element={<InterviewRoom />} />
      <Route path="/console-mianba" element={<AdminShell />} />
      <Route path="/terms" element={<LegalPage type="terms" />} />
      <Route path="/privacy" element={<LegalPage type="privacy" />} />
      <Route path="/refund" element={<LegalPage type="refund" />} />
      <Route path="/contact" element={<LegalPage type="contact" />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
