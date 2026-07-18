import { useState } from "react";
import type { InterviewType } from "../types";

/** 只在浏览器当前页面内持有个性化输入，并在会话创建成功后主动释放。 */
export function usePersonalizationInput() {
  const [jobResumeFile, setJobResumeFile] = useState<File | null>(null);
  const [jobTitle, setJobTitle] = useState("");
  const [jobRequirements, setJobRequirements] = useState("");
  const [postgraduateSchool, setPostgraduateSchool] = useState("");
  const [postgraduateMajor, setPostgraduateMajor] = useState("");
  const [postgraduateDirection, setPostgraduateDirection] = useState("");

  function isReady(interviewType: InterviewType) {
    if (interviewType === "job") {
      return Boolean(jobResumeFile && jobTitle.trim() && jobRequirements.trim());
    }
    if (interviewType === "postgraduate") {
      return Boolean(postgraduateSchool.trim() && postgraduateMajor.trim());
    }
    return true;
  }

  function createStartFormData(sessionId: string, interviewType: InterviewType) {
    if (!isReady(interviewType)) {
      throw new Error("personalization_input_incomplete");
    }
    const formData = new FormData();
    formData.append("session_id", sessionId);
    formData.append("interview_type", interviewType);
    if (interviewType === "job" && jobResumeFile) {
      formData.append("resume_file", jobResumeFile);
      formData.append("job_title", jobTitle.trim());
      formData.append("job_requirements", jobRequirements.trim());
    }
    if (interviewType === "postgraduate") {
      formData.append("target_school", postgraduateSchool.trim());
      formData.append("major", postgraduateMajor.trim());
      if (postgraduateDirection.trim()) {
        formData.append("research_direction", postgraduateDirection.trim());
      }
    }
    return formData;
  }

  function clear(interviewType: InterviewType) {
    if (interviewType === "job") {
      setJobResumeFile(null);
      setJobTitle("");
      setJobRequirements("");
    }
    if (interviewType === "postgraduate") {
      setPostgraduateSchool("");
      setPostgraduateMajor("");
      setPostgraduateDirection("");
    }
  }

  return {
    jobResumeFile,
    jobTitle,
    jobRequirements,
    postgraduateSchool,
    postgraduateMajor,
    postgraduateDirection,
    setJobResumeFile,
    setJobTitle,
    setJobRequirements,
    setPostgraduateSchool,
    setPostgraduateMajor,
    setPostgraduateDirection,
    isReady,
    createStartFormData,
    clear,
  };
}
