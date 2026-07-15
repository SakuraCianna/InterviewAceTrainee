import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ApiPayload } from "./types";
import type { InterviewMaterialResponse } from "./types";

type UploadInterviewMaterial = typeof import("./interviewApi").uploadInterviewMaterial;

const uploadMaterialMock = vi.hoisted(() => vi.fn<UploadInterviewMaterial>());

vi.mock("./interviewApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./interviewApi")>()),
  uploadInterviewMaterial: uploadMaterialMock,
}));

import { InterviewRoom } from "./InterviewRoom";

const material: InterviewMaterialResponse & ApiPayload = {
  id: "material-1",
  interview_type: "job",
  job_title: "AI 后端工程师",
  extracted_text_chars: 128,
  profile_summary: "候选人背景已分析。",
  keywords: ["Java"],
};

function materialResult(status: number, data: InterviewMaterialResponse & ApiPayload) {
  return {
    response: new Response(JSON.stringify(data), {
      status,
      headers: { "Content-Type": "application/json" },
    }),
    data,
    requestId: `request-${status}`,
  };
}

describe("InterviewRoom 素材上传幂等键", () => {
  beforeEach(() => {
    uploadMaterialMock.mockReset();
  });

  it("网络异常或 5xx 时复用原键，收到成功或明确业务失败后轮换", async () => {
    uploadMaterialMock
      .mockRejectedValueOnce(new TypeError("network unavailable"))
      .mockResolvedValueOnce(materialResult(503, { ...material, detail: "internal_server_error" }))
      .mockResolvedValueOnce(materialResult(200, material))
      .mockResolvedValueOnce(materialResult(422, { ...material, detail: "request_validation_failed" }))
      .mockResolvedValueOnce(materialResult(200, material));
    const user = userEvent.setup();
    const { container } = render(
      <MemoryRouter initialEntries={["/interview"]}>
        <InterviewRoom />
      </MemoryRouter>,
    );

    const fileInput = container.querySelector<HTMLInputElement>('input[type="file"]');
    expect(fileInput).not.toBeNull();
    await user.upload(fileInput as HTMLInputElement, new File(["resume"], "resume.txt", { type: "text/plain" }));
    await user.type(screen.getByPlaceholderText("例如：AI 后端工程师"), "AI 后端工程师");
    await user.type(screen.getByPlaceholderText("粘贴招聘 JD、岗位职责、加分项和技术要求"), "负责 Java AI 服务开发");

    await user.click(screen.getByRole("button", { name: "分析资料" }));
    expect(await screen.findByText("资料分析请求失败，请检查网络后重试。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "分析资料" }));
    expect(await screen.findByText("资料分析失败：服务器暂时不可用，请稍后重试。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "分析资料" }));
    expect(await screen.findByText("简历和岗位要求已分析，可以开始工作面试。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重新分析" }));
    expect(await screen.findByText("资料分析失败：提交内容格式不正确，请检查后重试。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重新分析" }));
    expect(await screen.findByText("简历和岗位要求已分析，可以开始工作面试。")).toBeInTheDocument();

    const operationKeys = uploadMaterialMock.mock.calls.map(([, options]) => options.idempotencyKey);
    expect(operationKeys).toHaveLength(5);
    expect(operationKeys[1]).toBe(operationKeys[0]);
    expect(operationKeys[2]).toBe(operationKeys[1]);
    expect(operationKeys[3]).not.toBe(operationKeys[2]);
    expect(operationKeys[4]).not.toBe(operationKeys[3]);
  });
});
