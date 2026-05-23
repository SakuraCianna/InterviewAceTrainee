import { useState } from "react";
import { BriefcaseBusiness, Building2, GraduationCap, Languages, Mic, Square, Volume2 } from "lucide-react";
import { AvatarStage, AvatarState } from "../../components/AvatarStage";

const states: AvatarState[] = ["idle", "speaking", "listening", "thinking"];
const modules = [
  { icon: BriefcaseBusiness, title: "工作面试", meta: "3 轮完整体验" },
  { icon: GraduationCap, title: "研究生复试", meta: "1 场复试模拟" },
  { icon: Building2, title: "考公面试", meta: "结构化训练" },
  { icon: Languages, title: "雅思口语", meta: "Part 1-3" }
];

export function InterviewRoom() {
  const [stateIndex, setStateIndex] = useState(1);
  const state = states[stateIndex];

  return (
    <main className="workspace-page interview-page">
      <header className="workspace-header">
        <a href="/" className="brand-mark">
          <span className="brand-dot" />
          面霸练习生
        </a>
        <span className="session-pill">选择模块 · 开始训练</span>
      </header>

      <section className="interview-layout">
        <AvatarStage state={state} />
        <aside className="interview-panel">
          <span className="eyebrow">Current Question</span>
          <h1>选择你的面试场景，然后开始语音模拟。</h1>
          <p>不同模块会匹配不同轮次、评分维度和报告结构。AI 提问后，用户语音回答，并手动点击回答完毕进入下一轮。</p>
          <div className="interview-module-list">
            {modules.map((module) => {
              const Icon = module.icon;
              return (
                <button type="button" key={module.title}>
                  <Icon size={18} />
                  <span>{module.title}</span>
                  <em>{module.meta}</em>
                </button>
              );
            })}
          </div>
          <div className="voice-controls">
            <button type="button" onClick={() => setStateIndex(2)}>
              <Mic size={18} />
              开始回答
            </button>
            <button type="button" onClick={() => setStateIndex(3)}>
              <Square size={18} />
              回答完毕
            </button>
            <button type="button" onClick={() => setStateIndex((value) => (value + 1) % states.length)}>
              <Volume2 size={18} />
              状态预览
            </button>
          </div>
        </aside>
      </section>
    </main>
  );
}
