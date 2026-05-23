import { Navigate, Route, Routes } from "react-router-dom";
import { AdminShell } from "./pages/admin/AdminShell";
import { AuthPage } from "./pages/AuthPage";
import { HomePage } from "./pages/HomePage";
import { InterviewRoom } from "./pages/interview/InterviewRoom";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/login" element={<AuthPage mode="login" />} />
      <Route path="/register" element={<AuthPage mode="register" />} />
      <Route path="/interview" element={<InterviewRoom />} />
      <Route path="/console-mianba" element={<AdminShell />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
