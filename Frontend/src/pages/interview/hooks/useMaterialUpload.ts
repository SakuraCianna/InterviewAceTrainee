import { useRef, useState } from "react";
import { createIdempotencyKey } from "../../../lib/api";
import { getLatestInterviewMaterial, uploadInterviewMaterial } from "../interviewApi";
import type { InterviewMaterialResponse, InterviewType } from "../types";

export type UseMaterialUploadResult = {
  materialsByType: Partial<Record<InterviewType, InterviewMaterialResponse>>;
  jobResumeFile: File | null;
  jobTitle: string;
  jobRequirements: string;
  postgraduateSchool: string;
  postgraduateMajor: string;
  postgraduateDirection: string;
  isPreparingMaterial: boolean;
  setJobResumeFile: (file: File | null) => void;
  setJobTitle: (v: string) => void;
  setJobRequirements: (v: string) => void;
  setPostgraduateSchool: (v: string) => void;
  setPostgraduateMajor: (v: string) => void;
  setPostgraduateDirection: (v: string) => void;
  setMaterialsByType: React.Dispatch<React.SetStateAction<Partial<Record<InterviewType, InterviewMaterialResponse>>>>;
  prepareMaterial: (interviewType: InterviewType, onMessage: (msg: string) => void) => Promise<void>;
  loadMaterialForType: (interviewType: InterviewType, signal?: AbortSignal) => Promise<void>;
};

export function useMaterialUpload(): UseMaterialUploadResult {
  const [materialsByType, setMaterialsByType] = useState<Partial<Record<InterviewType, InterviewMaterialResponse>>>({});
  const [jobResumeFile, setJobResumeFile] = useState<File | null>(null);
  const [jobTitle, setJobTitle] = useState("");
  const [jobRequirements, setJobRequirements] = useState("");
  const [postgraduateSchool, setPostgraduateSchool] = useState("");
  const [postgraduateMajor, setPostgraduateMajor] = useState("");
  const [postgraduateDirection, setPostgraduateDirection] = useState("");
  const [isPreparingMaterial, setIsPreparingMaterial] = useState(false);
  const materialOperationRef = useRef<{ fingerprint: string; idempotencyKey: string } | null>(null);

  async function loadMaterialForType(interviewType: InterviewType, signal?: AbortSignal) {
    try {
      const { response, data } = await getLatestInterviewMaterial(interviewType, signal);
      if (signal?.aborted) return;
      if (response.ok && data) {
        setMaterialsByType((prev) => ({ ...prev, [interviewType]: data }));
      }
    } catch {
      if (!signal?.aborted) {
        // 静默失败：材料恢复失败不影响主流程，用户可重新上传
      }
    }
  }

  async function prepareMaterial(interviewType: InterviewType, onMessage: (msg: string) => void) {
    setIsPreparingMaterial(true);
    const formData = new FormData();
    formData.append("interview_type", interviewType);
    if (interviewType === "job") {
      if (!jobResumeFile || !jobTitle.trim() || !jobRequirements.trim()) {
        onMessage("工作面试需要简历文件、目标岗位和岗位要求。");
        setIsPreparingMaterial(false);
        return;
      }
      formData.append("resume_file", jobResumeFile);
      formData.append("job_title", jobTitle.trim());
      formData.append("job_requirements", jobRequirements.trim());
    }
    if (interviewType === "postgraduate") {
      if (!postgraduateSchool.trim() || !postgraduateMajor.trim()) {
        onMessage("研究生复试需要先填写目标院校和报考专业。");
        setIsPreparingMaterial(false);
        return;
      }
      formData.append("target_school", postgraduateSchool.trim());
      formData.append("major", postgraduateMajor.trim());
      if (postgraduateDirection.trim()) {
        formData.append("research_direction", postgraduateDirection.trim());
      }
    }

    const fingerprint = JSON.stringify({
      interviewType,
      resumeFile: jobResumeFile && {
        name: jobResumeFile.name,
        size: jobResumeFile.size,
        type: jobResumeFile.type,
        lastModified: jobResumeFile.lastModified,
      },
      jobTitle: jobTitle.trim(),
      jobRequirements: jobRequirements.trim(),
      targetSchool: postgraduateSchool.trim(),
      major: postgraduateMajor.trim(),
      researchDirection: postgraduateDirection.trim(),
    });
    if (!materialOperationRef.current || materialOperationRef.current.fingerprint !== fingerprint) {
      materialOperationRef.current = { fingerprint, idempotencyKey: createIdempotencyKey() };
    }
    const operationKey = materialOperationRef.current.idempotencyKey;

    let result: Awaited<ReturnType<typeof uploadInterviewMaterial>>;
    try {
      result = await uploadInterviewMaterial(formData, { idempotencyKey: operationKey });
    } catch {
      // 断网或超时无法确认服务端是否已落库，保留本次操作键供用户重试。
      setIsPreparingMaterial(false);
      onMessage("资料分析请求失败，请检查网络后重试。");
      return;
    }
    const { response, data } = result;
    const hasDefinitiveOutcome = response.ok || (response.status >= 400 && response.status < 500);
    if (hasDefinitiveOutcome && materialOperationRef.current?.idempotencyKey === operationKey) {
      materialOperationRef.current = null;
    }
    setIsPreparingMaterial(false);

    if (!response.ok) {
      const { getApiErrorMessage } = await import("../../../lib/api");
      const errorMessage = getApiErrorMessage(data, "请检查文件和填写内容。");
      onMessage(`资料分析失败：${errorMessage}`);
      return;
    }

    setMaterialsByType((prev) => ({ ...prev, [data.interview_type]: data }));
    onMessage(
      data.interview_type === "job"
        ? "简历和岗位要求已分析，可以开始工作面试。"
        : "复试院校和专业信息已保存，可以开始模拟。",
    );
  }

  return {
    materialsByType,
    jobResumeFile,
    jobTitle,
    jobRequirements,
    postgraduateSchool,
    postgraduateMajor,
    postgraduateDirection,
    isPreparingMaterial,
    setJobResumeFile,
    setJobTitle,
    setJobRequirements,
    setPostgraduateSchool,
    setPostgraduateMajor,
    setPostgraduateDirection,
    setMaterialsByType,
    prepareMaterial,
    loadMaterialForType,
  };
}
