import { useLayoutEffect, useMemo, useRef, useState } from "react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { AnimatedCounter } from "../components/AnimatedCounter";
import { AppIcon } from "../components/AppIcon";
import { BrandLogo } from "../components/BrandLogo";

gsap.registerPlugin(ScrollTrigger);

type TrainingTrack = {
  icon: string;
  title: string;
  badge: string;
  mission: string;
  material: string;
  drill: string;
  report: string;
  cost: string;
  prompt: string;
  readiness: number;
  metrics: Array<[string, number]>;
  evidence: string[];
  nextStep: string;
};

type ReportCard = {
  label: string;
  score: string;
  verdict: string;
  metrics: Array<[string, number]>;
  evidence: string[];
};

const heroStats = [
  [250, "+", "岗位与目标画像"],
  [18, "", "能力评分维度"],
  [4, "", "训练产品线"],
];

const trainingTracks: TrainingTrack[] = [
  {
    icon: "lucide:briefcase-business",
    title: "工作面试",
    badge: "专业一面 / 二面 / HR 面",
    mission: "把岗位 JD、简历项目和技术栈变成连续追问链路, 训练项目复盘、技术取舍、压力追问和动机表达。",
    material: "简历 OCR, JD 匹配, 项目关键词",
    drill: "3 轮完整体验",
    report: "岗位匹配, 项目深挖, 沟通动机",
    cost: "2 次 / 深度模拟",
    prompt: "请用一个项目说明, 你如何在约束条件下做技术取舍?",
    readiness: 86,
    metrics: [
      ["专业能力", 88],
      ["项目深挖", 81],
      ["岗位匹配", 84],
    ],
    evidence: ["业务背景清楚", "指标还缺对照", "失败复盘偏短"],
    nextStep: "下一轮追问会继续深挖技术取舍、稳定性方案和上线后的指标复盘。",
  },
  {
    icon: "lucide:graduation-cap",
    title: "研究生复试",
    badge: "院校专业复试",
    mission: "围绕自我介绍、专业基础、科研兴趣、毕设和导师沟通设计问题, 让复试表达更像真实考场。",
    material: "目标院校, 报考专业, 研究方向",
    drill: "1 次单场模拟",
    report: "专业基础, 科研潜力, 表达结构",
    cost: "1 次 / 单场模拟",
    prompt: "请介绍你的研究兴趣, 并说明它和目标专业方向的关系。",
    readiness: 82,
    metrics: [
      ["专业基础", 76],
      ["科研潜力", 85],
      ["表达结构", 83],
    ],
    evidence: ["研究方向完整", "概念定义偏松", "导师沟通可更具体"],
    nextStep: "下一轮会追问专业概念边界、毕设方法和未来研究计划。",
  },
  {
    icon: "lucide:landmark",
    title: "考公面试",
    badge: "结构化题型训练",
    mission: "训练审题、层次、公共视角和稳定输出, 覆盖综合分析、组织协调、人际沟通和应急应变。",
    material: "无需上传材料",
    drill: "1 次结构化模拟",
    report: "审题立意, 逻辑层次, 稳定表达",
    cost: "1 次 / 单场模拟",
    prompt: "面对公共服务资源紧张的情况, 你会如何协调优先级?",
    readiness: 79,
    metrics: [
      ["审题立意", 82],
      ["逻辑层次", 78],
      ["现场稳定", 77],
    ],
    evidence: ["公共视角明确", "落地步骤略泛", "结尾升华不足"],
    nextStep: "下一轮会压缩准备时间, 训练分层作答和应急追问下的稳定输出。",
  },
  {
    icon: "lucide:languages",
    title: "雅思口语",
    badge: "Part 1 / 2 / 3",
    mission: "按真实口语节奏练流利度、词汇、语法、发音和观点展开, 输出下一轮可复用表达建议。",
    material: "无需上传材料",
    drill: "2 次口语全流程",
    report: "Fluency, Vocabulary, Grammar",
    cost: "2 次 / 完整口语",
    prompt: "Describe a time when you learned something difficult and explain why it mattered.",
    readiness: 74,
    metrics: [
      ["Fluency", 78],
      ["Vocabulary", 74],
      ["Grammar", 72],
    ],
    evidence: ["观点能展开", "连接词重复", "Part 3 对比不足"],
    nextStep: "下一轮会进入 Part 3 抽象讨论, 重点训练比较、原因和结果表达。",
  },
];

const practiceProtocol = [
  ["01", "画像建模", "目标岗位、院校专业或口语场景先进入训练配置, 系统决定题型结构和评分维度。"],
  ["02", "语音入场", "AI 面试官播报问题, 用户用自然语音作答, 页面只保留必要控制和状态反馈。"],
  ["03", "实时追问", "根据上一轮回答继续深挖, 模拟真实面试里最容易失控的二次追问。"],
  ["04", "证据复盘", "报告保留问题、回答、评分依据、风险提醒和下一轮训练建议。"],
];

const reportCards: ReportCard[] = [
  {
    label: "工作面试复盘",
    score: "86",
    verdict: "项目经历可信, 但技术取舍与失败复盘还可以更锋利。",
    metrics: [
      ["岗位匹配", 88],
      ["项目深挖", 81],
      ["表达结构", 84],
    ],
    evidence: ["能解释业务背景", "指标缺少对照", "风险复盘偏短"],
  },
  {
    label: "复试模拟报告",
    score: "82",
    verdict: "科研兴趣完整, 专业基础回答需要更有定义感。",
    metrics: [
      ["专业基础", 76],
      ["科研潜力", 85],
      ["导师沟通", 83],
    ],
    evidence: ["研究方向清晰", "概念边界松散", "毕设表达可压缩"],
  },
  {
    label: "雅思口语报告",
    score: "6.5",
    verdict: "观点能展开, 连接词和发音稳定性需要继续磨。",
    metrics: [
      ["Fluency", 78],
      ["Vocabulary", 74],
      ["Grammar", 72],
    ],
    evidence: ["话题卡完整", "句式重复", "Part 3 对比不足"],
  },
];

const productSignals = [
  ["lucide:radio", "Voice-first", "先听题, 再开口, 少打字, 更接近真实面试。"],
  ["lucide:route", "Adaptive follow-up", "追问跟着上一轮回答走, 不只是固定题库。"],
  ["lucide:file-search", "Evidence report", "报告写清楚为什么扣分, 下一轮该练哪里。"],
  ["lucide:coins", "Credit ledger", "开通、扣次、训练和报告都有记录可追。"],
];

const commandQueue = [
  ["简历/JD", "提取项目、岗位、关键词"],
  ["AI 面试官", "播报问题、追问、等待作答"],
  ["ASR/TTS", "语音识别与问题播报"],
  ["报告引擎", "维度评分、证据、建议"],
];

const capabilityLayers = [
  ["Input Layer", "资料结构化", "简历、JD、院校、专业、口语题卡进入统一画像, 先建立可追问节点。"],
  ["Interview Layer", "追问策略", "根据回答内容切换深挖、澄清、压力追问和节奏控制。"],
  ["Signal Layer", "语音与状态", "TTS 播题、ASR 转写、作答时长、断线恢复和训练状态同步反馈。"],
  ["Review Layer", "证据报告", "评分不只给结论, 还保留回答证据、风险点和下一轮训练动作。"],
];

const personaJourneys = [
  ["应届生", "项目经历很多, 但讲不出岗位相关性。", "从 JD 关键词反推项目表达, 训练 STAR、指标和技术取舍。"],
  ["转码/转岗", "背景跨度大, 容易被追问动机和能力迁移。", "把过往经历拆成能力证据, 强化岗位匹配与稳定性表达。"],
  ["复试考生", "自我介绍完整, 但专业追问容易散。", "用院校专业画像组织概念、毕设、科研兴趣和导师沟通。"],
  ["口语备考", "能说, 但 Part 2/3 展开和连接不稳。", "按真实节奏练话题卡、抽象讨论、词汇替换和语法稳定。"],
];

const operatingMetrics = [
  ["画像完成度", "92%", "材料节点已拆解"],
  ["追问命中率", "87%", "上一轮回答驱动"],
  ["复盘可执行", "18", "维度进入报告"],
];

export function HomePage() {
  const rootRef = useRef<HTMLElement | null>(null);
  const [activeTrackIndex, setActiveTrackIndex] = useState(0);
  const [sessionRound, setSessionRound] = useState(1);
  const [isRecording, setIsRecording] = useState(false);

  const activeTrack = trainingTracks[activeTrackIndex];
  const sessionMetrics = useMemo(
    () => activeTrack.metrics.map(([label, value], index) => [label, Math.min(96, value + sessionRound + (isRecording && index === 0 ? 2 : 0))] as [string, number]),
    [activeTrack, isRecording, sessionRound],
  );
  const sessionScore = useMemo(() => Math.min(96, activeTrack.readiness + sessionRound + (isRecording ? 1 : 0)), [activeTrack, isRecording, sessionRound]);
  const sessionStatus = isRecording ? "正在采集回答" : sessionRound === 1 ? "等待开口" : "已生成上一轮复盘";

  useLayoutEffect(() => {
    if (!rootRef.current || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      return;
    }

    const ctx = gsap.context(() => {
      const tl = gsap.timeline({ defaults: { ease: "power3.out" } });
      tl.from(".mission-nav", { y: -18, duration: 0.5, clearProps: "transform" })
        .from(".mission-hero-copy .mission-reveal", { y: 34, duration: 0.72, stagger: 0.07, clearProps: "transform" }, "-=0.12")
        .from(".mission-orchestrator", { y: 38, scale: 0.985, duration: 0.78, clearProps: "transform" }, "-=0.46");

      gsap.to(".mission-wave span", {
        scaleY: 0.34,
        duration: 0.9,
        ease: "sine.inOut",
        stagger: 0.055,
        repeat: -1,
        yoyo: true,
      });

      gsap.utils.toArray<HTMLElement>(".mission-scroll-section").forEach((section) => {
        const items = Array.from(section.querySelectorAll<HTMLElement>(".mission-motion"));
        if (items.length === 0) {
          return;
        }
        gsap.from(items, {
          scrollTrigger: {
            trigger: section,
            start: "top 80%",
            once: true,
          },
          y: 28,
          duration: 0.62,
          stagger: 0.055,
          ease: "power2.out",
          clearProps: "transform",
        });
      });
    }, rootRef);

    const refreshFrame = window.requestAnimationFrame(() => ScrollTrigger.refresh(true));
    return () => {
      window.cancelAnimationFrame(refreshFrame);
      ctx.revert();
    };
  }, []);

  useLayoutEffect(() => {
    if (!rootRef.current || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      return;
    }

    const ctx = gsap.context(() => {
      gsap.fromTo(
        ".mission-live .mission-question, .mission-report-snapshot, .mission-demo-evidence li",
        { y: 10, autoAlpha: 0.72 },
        { y: 0, autoAlpha: 1, duration: 0.36, stagger: 0.035, ease: "power2.out", overwrite: "auto", clearProps: "transform,opacity,visibility" },
      );
      gsap.fromTo(".mission-agent-core > div:first-child", { scale: 0.965 }, { scale: 1, duration: 0.42, ease: "power2.out", overwrite: "auto", clearProps: "transform" });
    }, rootRef);

    return () => ctx.revert();
  }, [activeTrackIndex, sessionRound, isRecording]);

  return (
    <main className="mianba-home mianba-home--mission" ref={rootRef}>
      <nav className="mission-nav" aria-label="主导航">
        <a className="mission-brand" href="/" aria-label="面霸练习生首页">
          <BrandLogo size={30} />
          <span>面霸练习生</span>
        </a>
        <div className="mission-nav-links">
          <a href="#system">产品系统</a>
          <a href="#matrix">训练矩阵</a>
          <a href="#protocol">训练协议</a>
          <a href="#reports">复盘报告</a>
        </div>
        <a className="mission-nav-action" href="/login">
          进入训练台
          <AppIcon icon="lucide:arrow-right" size={17} />
        </a>
      </nav>

      <header className="mission-hero">
        <section className="mission-hero-copy" aria-labelledby="home-title">
          <p className="mission-kicker mission-reveal">AI Interview Training OS</p>
          <h1 className="mission-reveal" id="home-title">把面试练习, 做成一套可复盘的作战系统。</h1>
          <p className="mission-lead mission-reveal">
            面霸练习生不是聊天机器人。它把目标画像、语音面试官、实时追问、评分报告和次数账本串成一条训练链路, 让每一次开口都有上下文、有证据、有下一步。
          </p>
          <div className="mission-actions mission-reveal">
            <a className="mission-primary" href="/register">
              创建账号
              <AppIcon icon="lucide:arrow-right" size={18} />
            </a>
            <a className="mission-secondary" href="#matrix">
              查看训练矩阵
              <AppIcon icon="lucide:chevron-down" size={18} />
            </a>
          </div>
          <div className="mission-stat-grid mission-reveal">
            {heroStats.map(([value, suffix, label]) => (
              <article key={label}>
                <AnimatedCounter value={Number(value)} suffix={String(suffix)} />
                <span>{label}</span>
              </article>
            ))}
          </div>
        </section>

        <section className="mission-orchestrator" aria-label="产品操作台预览">
          <div className="mission-orchestrator-head">
            <span>
              <AppIcon icon="lucide:activity" size={17} />
              Live Practice Room
            </span>
            <em>点击左侧模块切换问题</em>
            <strong>{isRecording ? "作答中" : `第 ${sessionRound} / 3 轮`}</strong>
          </div>
          <div className="mission-orchestrator-grid">
            <aside className="mission-queue mission-training-tabs" aria-label="训练模块预览">
              {trainingTracks.map((track, index) => (
                <button
                  type="button"
                  className={index === activeTrackIndex ? "is-active" : ""}
                  key={track.title}
                  onClick={() => {
                    setActiveTrackIndex(index);
                    setSessionRound(1);
                    setIsRecording(false);
                  }}
                  aria-pressed={index === activeTrackIndex}
                >
                  <AppIcon icon={track.icon} size={24} />
                  <span>
                    <strong>{track.title}</strong>
                    <small>{track.badge}</small>
                    <em>{track.drill}</em>
                  </span>
                </button>
              ))}
            </aside>
            <div className="mission-live">
              <div className="mission-live-status" aria-live="polite">
                <span>{sessionStatus}</span>
                <b>{activeTrack.cost}</b>
              </div>
              <div className="mission-question">
                <span>{activeTrack.title}面试官提问</span>
                <p>{activeTrack.prompt}</p>
              </div>
              <div className={isRecording ? "mission-agent-core is-recording" : "mission-agent-core"}>
                <div>
                  <AppIcon icon="lucide:audio-lines" size={56} />
                </div>
                <div className="mission-wave" aria-hidden="true">
                  {Array.from({ length: 21 }).map((_, index) => (
                    <span key={index} style={{ height: `${18 + ((index * 7) % 46)}px` }} />
                  ))}
                </div>
              </div>
              <div className="mission-live-actions">
                <button type="button" onClick={() => setIsRecording((value) => !value)} aria-pressed={isRecording}>
                  <AppIcon icon={isRecording ? "lucide:pause" : "lucide:mic"} size={18} />
                  {isRecording ? "暂停回答" : "开始回答"}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setIsRecording(false);
                    setSessionRound((round) => (round >= 3 ? 1 : round + 1));
                  }}
                >
                  <AppIcon icon="lucide:square" size={18} />
                  回答完毕
                </button>
              </div>
            </div>
            <aside className="mission-report-snapshot">
              <span>Report Snapshot</span>
              <strong>{sessionScore}</strong>
              <p>{activeTrack.nextStep}</p>
              {sessionMetrics.map(([label, value]) => (
                <div key={label}>
                  <b>{label}</b>
                  <meter min="0" max="100" value={value} />
                </div>
              ))}
              <ul className="mission-demo-evidence">
                {activeTrack.evidence.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </aside>
          </div>
        </section>
      </header>

      <section className="mission-signal-strip mission-scroll-section">
        {productSignals.map(([icon, title, copy]) => (
          <article className="mission-motion" key={title}>
            <AppIcon icon={icon} size={22} />
            <div>
              <h2>{title}</h2>
              <p>{copy}</p>
            </div>
          </article>
        ))}
      </section>

      <section className="mission-system mission-scroll-section" id="system">
        <div className="mission-section-head mission-motion">
          <p className="mission-kicker">Product System</p>
          <h2>它不是一个题库页面, 而是一套从资料到复盘的训练操作系统。</h2>
          <p>主界面把用户最关心的三件事放到前台: 现在练什么、AI 会怎么追问、报告能给出什么下一步。</p>
        </div>
        <div className="mission-system-grid">
          <article className="mission-layer-card mission-motion">
            <div className="mission-panel-head">
              <span>System Layers</span>
              <AppIcon icon="lucide:layers-3" size={22} />
            </div>
            {capabilityLayers.map(([label, title, copy]) => (
              <div className="mission-layer-row" key={label}>
                <small>{label}</small>
                <strong>{title}</strong>
                <p>{copy}</p>
              </div>
            ))}
          </article>

          <article className="mission-ops-card mission-motion">
            <div className="mission-panel-head">
              <span>Live Readiness</span>
              <AppIcon icon="lucide:radar" size={22} />
            </div>
            <div className="mission-ops-metrics">
              {operatingMetrics.map(([label, value, copy]) => (
                <div key={label}>
                  <strong>{value}</strong>
                  <span>{label}</span>
                  <p>{copy}</p>
                </div>
              ))}
            </div>
            <div className="mission-pipeline-list">
              {commandQueue.map(([title, copy], index) => (
                <div key={title}>
                  <span>{String(index + 1).padStart(2, "0")}</span>
                  <strong>{title}</strong>
                  <p>{copy}</p>
                </div>
              ))}
            </div>
          </article>
        </div>
      </section>

      <section className="mission-section mission-scroll-section" id="matrix">
        <div className="mission-section-head mission-motion">
          <p className="mission-kicker">Training Matrix</p>
          <h2>四类训练不是换标题, 而是换材料、追问策略和评分模型。</h2>
          <p>进入房间前先选择目标。系统会根据模块切换资料要求、轮次结构、问题风格和报告模板。</p>
        </div>
        <div className="mission-track-grid">
          {trainingTracks.map((track, index) => (
            <article className="mission-track-card mission-motion" key={track.title}>
              <div className="mission-card-index">
                <AppIcon icon={track.icon} size={26} />
                <span>{String(index + 1).padStart(2, "0")}</span>
              </div>
              <h3>{track.title}</h3>
              <strong>{track.badge}</strong>
              <p>{track.mission}</p>
              <dl>
                <div>
                  <dt>材料入口</dt>
                  <dd>{track.material}</dd>
                </div>
                <div>
                  <dt>训练节奏</dt>
                  <dd>{track.drill}</dd>
                </div>
                <div>
                  <dt>报告重点</dt>
                  <dd>{track.report}</dd>
                </div>
              </dl>
              <em>{track.cost}</em>
            </article>
          ))}
        </div>
      </section>

      <section className="mission-section mission-scroll-section" id="playbooks">
        <div className="mission-section-head mission-motion">
          <p className="mission-kicker">Interview Playbooks</p>
          <h2>不同用户进入同一套产品, 看到的是不同训练路径。</h2>
          <p>主界面需要让用户立刻找到自己的处境: 应届生、转岗、复试、口语备考, 每一种都对应不同的材料、追问和复盘侧重点。</p>
        </div>
        <div className="mission-persona-grid">
          {personaJourneys.map(([label, pain, plan], index) => (
            <article className="mission-persona-card mission-motion" key={label}>
              <span>{String(index + 1).padStart(2, "0")}</span>
              <h3>{label}</h3>
              <p>{pain}</p>
              <strong>{plan}</strong>
            </article>
          ))}
        </div>
      </section>

      <section className="mission-protocol mission-scroll-section" id="protocol">
        <div className="mission-section-head mission-motion">
          <p className="mission-kicker">Practice Protocol</p>
          <h2>一次训练从入场到报告, 每个阶段都有明确任务。</h2>
        </div>
        <div className="mission-protocol-grid">
          {practiceProtocol.map(([number, title, copy]) => (
            <article className="mission-motion" key={number}>
              <span>{number}</span>
              <h3>{title}</h3>
              <p>{copy}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="mission-section mission-scroll-section" id="reports">
        <div className="mission-section-head mission-motion">
          <p className="mission-kicker">Report Dossier</p>
          <h2>报告页要像复盘档案, 而不是一句“表现不错”。</h2>
          <p>每份报告都包含综合评分、维度得分、证据、风险提醒和下一轮动作, 用来指导下一次训练。</p>
        </div>
        <div className="mission-report-grid">
          {reportCards.map((report) => (
            <article className="mission-report-card mission-motion" key={report.label}>
              <div>
                <span>{report.label}</span>
                <strong>{report.score}</strong>
              </div>
              <h3>{report.verdict}</h3>
              <div className="mission-report-bars">
                {report.metrics.map(([label, value]) => (
                  <label key={label}>
                    <span>{label}</span>
                    <meter min="0" max="100" value={value} />
                    <b>{value}</b>
                  </label>
                ))}
              </div>
              <ul>
                {report.evidence.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </article>
          ))}
        </div>
      </section>

      <section className="mission-credit mission-scroll-section" id="credits">
        <div className="mission-credit-copy mission-motion">
          <p className="mission-kicker">Credit & Support</p>
          <h2>新人 1 张体验券, 正式训练按模块扣次, 后台全程可追。</h2>
          <p>需要开通训练次数时, 可添加官方微信 Teptysuki666。系统会保留开通、扣次、训练和报告记录, 售后复核也能回到同一条证据链。</p>
          <a className="mission-primary" href="/register">
            先创建账号
            <AppIcon icon="lucide:arrow-right" size={18} />
          </a>
        </div>
        <div className="mission-credit-table mission-motion">
          {trainingTracks.map((track) => (
            <div key={track.title}>
              <span>
                <AppIcon icon={track.icon} size={18} />
                {track.title}
              </span>
              <strong>{track.cost}</strong>
            </div>
          ))}
        </div>
      </section>

      <section className="mission-final">
        <BrandLogo size={50} />
        <h2>把紧张留在练习里, 把稳定带到真正上场时。</h2>
        <a className="mission-final-action" href="/register">
          开始第一次模拟
          <AppIcon icon="lucide:arrow-right" size={18} />
        </a>
      </section>

      <footer className="mission-footer">
        <div>
          <BrandLogo size={32} />
          <span>面霸练习生</span>
        </div>
        <nav aria-label="底部链接">
          <a href="/terms">使用条款</a>
          <a href="/privacy">隐私政策</a>
          <a href="/refund">退款与纠纷</a>
          <a href="/contact">联系我们</a>
        </nav>
        <p>本产品用于面试和口语模拟训练, 报告仅作为复盘参考, 不承诺录取、上岸或考试成绩结果。</p>
      </footer>
    </main>
  );
}
