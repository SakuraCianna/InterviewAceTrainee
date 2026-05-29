import type { AvatarState } from "../../components/AvatarStage";
import type { InterviewType } from "./types";

export const states: AvatarState[] = ["idle", "speaking", "listening", "thinking"];
export const modules: { icon: string; title: string; meta: string; type: InterviewType; lang: string }[] = [
  { icon: "lucide:briefcase-business", title: "工作面试", meta: "专业一面 + 专业二面 + HR 面", type: "job", lang: "zh-CN" },
  { icon: "lucide:graduation-cap", title: "研究生复试", meta: "自我介绍、专业基础、科研潜力", type: "postgraduate", lang: "zh-CN" },
  { icon: "lucide:landmark", title: "考公面试", meta: "综合分析、组织协调、应急应变", type: "civil_service", lang: "zh-CN" },
  { icon: "lucide:languages", title: "雅思口语", meta: "Part 1 / 2 / 3 全流程", type: "ielts", lang: "en-US" },
];

export const moduleDetails: Record<
  InterviewType,
  {
    badge: string;
    intro: string;
    rounds: string;
    material: string;
    report: string;
    checklist: string[];
  }
> = {
  job: {
    badge: "3 轮完整体验",
    intro: "面向校招、社招和转岗求职，系统会结合简历、目标岗位和 JD 追问项目细节、岗位匹配、沟通动机与 HR 压力题。",
    rounds: "专业一面、专业二面、HR 面",
    material: "开始前需要上传简历，并填写应聘岗位和岗位要求。",
    report: "输出岗位匹配度、项目深挖表现、风险点、示范回答和下一轮专项训练建议。",
    checklist: ["简历 OCR 预分析", "JD 与能力关键词匹配", "二面追问链路", "HR 动机与稳定性"],
  },
  postgraduate: {
    badge: "1 次单场模拟",
    intro: "面向研究生复试，围绕院校、目标专业、研究方向、自我介绍、专业基础和导师沟通组织问题。",
    rounds: "复试综合问答",
    material: "开始前需要填写目标院校、报考专业，研究方向可选填。",
    report: "输出专业基础、科研潜力、表达结构、导师沟通准备度和补强路线。",
    checklist: ["院校专业背景", "自我介绍结构", "专业基础追问", "科研兴趣表达"],
  },
  civil_service: {
    badge: "1 次结构化模拟",
    intro: "面向考公结构化面试，按综合分析、组织协调、人际沟通、应急应变等常见题型训练稳定表达。",
    rounds: "结构化题型组",
    material: "无需上传材料，选择后直接进入设备检测。",
    report: "输出审题准确性、公共视角、表达层次、落地措施和临场稳定性。",
    checklist: ["审题拆解", "观点与层次", "公共治理视角", "应急处置逻辑"],
  },
  ielts: {
    badge: "2 次口语全流程",
    intro: "面向 IELTS Speaking，按 Part 1、Part 2、Part 3 节奏进行英文口语问答和延展追问。",
    rounds: "Part 1 / Part 2 / Part 3",
    material: "无需上传材料，选择后直接进入设备检测。",
    report: "输出流利度、词汇、语法、发音、观点展开和可复用表达建议。",
    checklist: ["日常问答", "话题卡展开", "抽象讨论", "表达替换建议"],
  },
};
