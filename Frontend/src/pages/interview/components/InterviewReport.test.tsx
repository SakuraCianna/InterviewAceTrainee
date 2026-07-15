import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { InterviewReport as InterviewReportData } from "../types";
import { InterviewReport } from "./InterviewReport";

function createReport(interviewType: InterviewReportData["interview_type"]): InterviewReportData {
  return {
    session_id: "11111111-1111-4111-8111-111111111111",
    interview_type: interviewType,
    total_score: 7,
    readiness_level: "",
    score_explanation: "",
    summary: "The response was clear and relevant.",
    dimensions: [],
    strengths: [],
    improvements: [],
    next_plan: [],
    priority_actions: [],
    evidence: [],
    risk_flags: [],
    recommended_drills: ["Part 2 fluency drill"],
    turns: [],
  };
}

describe("InterviewReport", () => {
  it("IELTS 报告的全部界面文案均为英文", () => {
    const { container } = render(<InterviewReport report={createReport("ielts")} />);

    expect(screen.getByRole("heading", { name: "IELTS Speaking Review" })).toBeInTheDocument();
    expect(screen.getByText("Overall Score")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Assessment Criteria" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Evidence" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Priority Actions" })).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/[\u3400-\u9fff]/u);
  });

  it("非 IELTS 报告继续使用中文界面文案", () => {
    render(<InterviewReport report={createReport("job")} />);

    expect(screen.getByText("综合得分")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "能力维度" })).toBeInTheDocument();
  });
});
