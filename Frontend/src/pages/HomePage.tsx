import { useLayoutEffect, useRef, useState } from "react";
import type { PointerEvent } from "react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { AppIcon } from "../components/AppIcon";
import { BrandLogo } from "../components/BrandLogo";

gsap.registerPlugin(ScrollTrigger);

type TrainingTrack = {
  icon: string;
  title: string;
  badge: string;
  description: string;
  checkpoints: string[];
  cost: string;
  preview: {
    question: string;
    followup: string;
    scores: Array<[string, number]>;
  };
};

type ReportCard = {
  label: string;
  score: string;
  title: string;
  metrics: Array<[string, number]>;
  summary: string;
  evidence: string[];
  nextActions: string[];
  reminder: string;
  advice: string;
};

const trainingTracks: TrainingTrack[] = [
  {
    icon: "lucide:briefcase-business",
    title: "工作面试",
    badge: "专业一面 / 二面 / HR 面",
    description:
      "围绕岗位要求、简历项目、技术栈、业务指标、团队协作和职业动机做连续追问。系统会先识别岗位 JD 与简历经历之间的对应关系，再把项目背景、技术取舍、故障复盘、上线效果和跨部门沟通拆成多轮问题，适合正式一面、二面和 HR 面前做高压预演。",
    checkpoints: ["岗位关键词匹配", "项目架构与指标复盘", "技术取舍与失败案例", "HR 动机与稳定性表达"],
    cost: "2 次 / 深度模拟",
    preview: {
      question: "请用一个项目说明, 你如何在约束条件下做技术取舍?",
      followup: "刚才你提到了性能优化, 如果上线后错误率突然升高, 你会如何定位并向业务方同步风险?",
      scores: [
        ["专业能力", 88],
        ["项目深挖", 81],
        ["岗位匹配", 84],
      ],
    },
  },
  {
    icon: "lucide:graduation-cap",
    title: "研究生复试",
    badge: "院校专业复试",
    description:
      "把自我介绍、报考动机、专业基础、毕业设计、科研兴趣、论文阅读和导师沟通拆成可练的问答链路。系统会围绕目标院校、报考专业和研究方向组织追问，帮助你把“我想研究什么、为什么适合这个方向、目前准备到什么程度”讲清楚。",
    checkpoints: ["自我介绍结构", "专业基础概念", "科研兴趣与毕设", "导师沟通与培养匹配"],
    cost: "1 次 / 单场模拟",
    preview: {
      question: "请用 90 秒介绍你的本科经历、报考动机以及未来想深入的研究方向。",
      followup: "你提到对这个方向感兴趣, 能不能结合一篇论文或毕业设计说明你已经做过哪些准备?",
      scores: [
        ["专业基础", 76],
        ["科研潜力", 85],
        ["导师沟通", 83],
      ],
    },
  },
  {
    icon: "lucide:landmark",
    title: "考公面试",
    badge: "结构化题型训练",
    description:
      "训练审题、立意、层次、公共服务视角和稳定输出，覆盖综合分析、组织协调、人际沟通、应急应变、群众工作和现场表达。系统会保留每轮作答结构，帮助你检查是否做到观点明确、措施可落地、语言稳健且符合法治与公共治理基本要求。",
    checkpoints: ["综合分析立意", "组织协调流程", "应急应变处置", "公共服务与法治意识"],
    cost: "1 次 / 单场模拟",
    preview: {
      question: "有群众反映线上办事流程复杂, 老年人不会操作, 你作为窗口工作人员会怎么处理?",
      followup: "如果现场还有其他群众排队并出现不满情绪, 你会如何兼顾秩序、效率和群众体验?",
      scores: [
        ["审题立意", 82],
        ["措施落地", 79],
        ["公共视角", 86],
      ],
    },
  },
  {
    icon: "lucide:languages",
    title: "雅思口语",
    badge: "Part 1 / 2 / 3",
    description:
      "按 Part 1 / Part 2 / Part 3 的真实口语节奏训练流利度、词汇多样性、语法准确度、发音稳定性和观点展开。系统会根据回答长度、连接方式、举例质量和抽象讨论能力给出复盘，帮助你把准备过的素材转化成自然表达。",
    checkpoints: ["流利度与停顿", "话题卡结构", "词汇替换", "Part 3 抽象观点"],
    cost: "2 次 / 完整口语",
    preview: {
      question: "Describe a skill you learned recently. You should say what it was, how you learned it, and why it was useful.",
      followup: "Why do some people give up learning new skills quickly, and what can schools or companies do to change that?",
      scores: [
        ["Fluency", 78],
        ["Vocabulary", 74],
        ["Grammar", 72],
      ],
    },
  },
];

const heroStats = [
  ["250", "+", "岗位方向"],
  ["100", "+", "复试专业"],
  ["4", "", "训练产品"],
];

const practiceSteps = [
  ["01", "选目标", "选择工作、复试、考公或雅思，进入对应问题库和评分维度。"],
  ["02", "听提问", "数字面试官直接播报问题，页面不把答案藏在聊天框里。"],
  ["03", "开口答", "全程用语音组织表达，回答结束后手动进入下一轮。"],
  ["04", "被追问", "AI 根据上一轮内容继续深挖，保留真实面试的压力感。"],
  ["05", "拿报告", "输出评分、追问链路、短板、建议和下一轮训练重点。"],
];

const reportCards: ReportCard[] = [
  {
    label: "工作面试复盘",
    score: "86",
    title: "项目表达清楚，但二面追问还需要更稳。",
    metrics: [
      ["专业能力", 88],
      ["项目深挖", 81],
      ["岗位匹配", 84],
    ],
    summary:
      "本次回答能说明项目背景、个人职责和主要技术方案，能够把岗位要求与过往项目经历建立联系。主要短板集中在二面深挖阶段：当问题进入容量估算、异常恢复、成本约束和上线后指标时，回答还偏概括，需要补充可验证数据和失败复盘。",
    evidence: ["能够说明业务目标和个人职责", "提到了核心技术栈与上线结果", "缺少关键指标前后对比", "故障处理和复盘闭环不够完整"],
    nextActions: ["准备 1 个高并发或高可用项目的 90 秒版本", "补齐 QPS、延迟、错误率、成本等指标", "整理一次失败案例和改进动作", "练习用 STAR + 指标 + 取舍讲项目"],
    reminder: "报告只用于训练复盘，不构成招聘录用、面试通过或薪资结果承诺。",
    advice: "下一轮重点练高并发项目里的取舍、指标和失败复盘。",
  },
  {
    label: "复试模拟报告",
    score: "82",
    title: "科研兴趣完整，专业基础回答略松散。",
    metrics: [
      ["专业基础", 76],
      ["科研潜力", 85],
      ["导师沟通", 83],
    ],
    summary:
      "本次复试模拟中，自我介绍和研究兴趣表达较完整，能够说明报考动机和未来方向。需要加强的是专业基础概念的定义感，以及把毕业设计、论文阅读和目标导师方向串成一条清晰证据链，避免回答停留在“感兴趣”和“愿意努力”。",
    evidence: ["报考动机表达自然", "研究兴趣能对应到专业方向", "核心概念边界回答略松散", "毕设方法和预期结果说明不足"],
    nextActions: ["把 3 个核心专业概念写成定义卡片", "准备毕设研究问题、方法和不足", "整理 2 篇论文阅读收获", "用 90 秒说明为什么适合该方向"],
    reminder: "复试训练报告仅供备考参考，院校招生政策、复试规则和录取结果以官方通知为准。",
    advice: "建议把毕设、论文阅读和研究方向整理成 90 秒表达。",
  },
  {
    label: "雅思口语报告",
    score: "6.5",
    title: "观点能展开，连接词和发音稳定性可继续提高。",
    metrics: [
      ["Fluency", 78],
      ["Vocabulary", 74],
      ["Grammar", 72],
    ],
    summary:
      "本次口语回答能够完成基本观点展开，Part 2 叙事结构完整，Part 3 可以给出原因和例子。继续提升的重点是减少连接词重复，增加同义替换和复杂句准确度，并在抽象问题中加入对比、让步和结果分析。",
    evidence: ["Part 2 能完整描述事件", "观点表达有基本例子支撑", "连接词重复较明显", "复杂句准确度和发音稳定性仍需训练"],
    nextActions: ["准备 8 组高频话题的替换词", "训练 Part 3 的 compare / reason / impact 结构", "每次回答后复听停顿和重音", "把短句合并成更自然的复合句"],
    reminder: "口语报告为练习反馈，不代表 IELTS 官方评分或最终考试成绩。",
    advice: "Part 2 继续练故事结构，Part 3 增加对比和原因解释。",
  },
];

const trustItems = [
  ["lucide:mic-2", "语音优先", "从听题到回答都按真实开口节奏推进。"],
  ["lucide:file-text", "复盘可追", "保留问题、回答、追问和报告维度。"],
  ["lucide:shield-check", "边界清楚", "报告用于训练复盘，不承诺录取或考试结果。"],
];

const creditNotes = [
  ["开通前", "先注册账号并确认登录邮箱。客服只需要核对账号邮箱和训练模块，不会索要邮箱验证码、密码或支付平台验证码。"],
  ["扣次规则", "工作面试和雅思口语属于完整流程训练，按页面标注扣除对应次数；研究生复试和考公面试按单场模拟扣次。训练开始前请确认模块选择。"],
  ["异常处理", "若因系统故障导致训练无法完成、语音识别中断或报告未生成，可提供账号邮箱、训练时间和截图，由客服按日志核实后补发次数或协商处理。"],
  ["记录留存", "系统会保留开通、扣次、训练轮次、报告生成和售后处理记录，用于用户查询、纠纷处理和安全审计。"],
];

const filingLinks = {
  icpNumber: import.meta.env.VITE_ICP_NUMBER || "湘ICP备2025151258号-1",
  policeNumber: import.meta.env.VITE_POLICE_RECORD_NUMBER || "",
  icpUrl: "https://beian.miit.gov.cn/",
  policeUrl: "https://beian.mps.gov.cn/#/query/webSearch",
};

export function HomePage() {
  const rootRef = useRef<HTMLDivElement>(null);
  const [activeTrackIndex, setActiveTrackIndex] = useState(0);
  const [previewPhase, setPreviewPhase] = useState<"ready" | "recording" | "followup">("ready");
  const activeTrack = trainingTracks[activeTrackIndex];
  const previewQuestion = previewPhase === "followup" ? activeTrack.preview.followup : activeTrack.preview.question;
  const cockpitStatus =
    previewPhase === "recording" ? "正在回答" : previewPhase === "followup" ? "追问已生成" : "第 1 / 3 轮";

  function handleSpotlightMove(event: PointerEvent<HTMLElement>) {
    const target = (event.target as HTMLElement).closest<HTMLElement>(".mianba-spotlight");
    if (!target) {
      return;
    }

    const rect = target.getBoundingClientRect();
    target.style.setProperty("--spot-x", `${event.clientX - rect.left}px`);
    target.style.setProperty("--spot-y", `${event.clientY - rect.top}px`);
  }

  useLayoutEffect(() => {
    if (!rootRef.current || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      return;
    }

    const context = gsap.context(() => {
      gsap.from(".mianba-nav", { y: -18, duration: 0.65, ease: "power3.out" });
      gsap.from(".mianba-hero-copy .mianba-reveal", {
        y: 32,
        duration: 0.82,
        ease: "power4.out",
        stagger: 0.08,
      });
      gsap.from(".mianba-cockpit", {
        y: 38,
        rotateX: 3,
        duration: 0.9,
        delay: 0.12,
        ease: "power4.out",
      });
      gsap.to(".mianba-wave span", {
        scaleY: 0.3,
        duration: 0.84,
        ease: "sine.inOut",
        stagger: 0.06,
        repeat: -1,
        yoyo: true,
      });
      gsap.utils.toArray<HTMLElement>(".mianba-ticker").forEach((ticker) => {
        const targetValue = Number(ticker.dataset.value || "0");
        const suffix = ticker.dataset.suffix || "";
        const decimals = Number(ticker.dataset.decimals || "0");
        const state = { value: targetValue };

        gsap.fromTo(state, {
          value: 0,
        }, {
          value: targetValue,
          duration: 1.35,
          ease: "power3.out",
          immediateRender: false,
          scrollTrigger: {
            trigger: ticker,
            start: "top 88%",
            once: true,
          },
          onUpdate: () => {
            ticker.textContent = `${state.value.toFixed(decimals)}${suffix}`;
          },
        });
      });
      gsap.to(".mianba-flow-beam", {
        scaleX: 1,
        duration: 1.4,
        ease: "power3.out",
        scrollTrigger: {
          trigger: ".mianba-workflow",
          start: "top 72%",
          once: true,
        },
      });
      gsap.utils.toArray<HTMLElement>(".mianba-scroll-section").forEach((section) => {
        const items = Array.from(section.querySelectorAll<HTMLElement>(".mianba-motion"));
        if (items.length === 0) {
          return;
        }

        gsap.from(items, {
          scrollTrigger: {
            trigger: section,
            start: "top 78%",
            toggleActions: "play none none none",
            once: true,
          },
          y: 34,
          duration: 0.75,
          clearProps: "transform",
          stagger: 0.08,
          ease: "power2.out",
        });
      });
    }, rootRef);

    const refreshFrame = window.requestAnimationFrame(() => {
      ScrollTrigger.refresh(true);
    });

    return () => {
      window.cancelAnimationFrame(refreshFrame);
      context.revert();
    };
  }, []);

  return (
    <main className="mianba-home" onPointerMove={handleSpotlightMove} ref={rootRef}>
      <nav className="mianba-nav" aria-label="主导航">
        <a className="mianba-brand" href="/" aria-label="面霸练习生首页">
          <BrandLogo size={30} />
          <span>面霸练习生</span>
        </a>
        <div className="mianba-nav-links">
          <a href="#tracks">训练模块</a>
          <a href="#workflow">练习流程</a>
          <a href="#reports">复盘报告</a>
          <a href="#credits">开通方式</a>
        </div>
        <a className="mianba-nav-action" href="/login">
          登录
          <AppIcon icon="lucide:arrow-right" size={17} />
        </a>
      </nav>

      <header className="mianba-hero">
        <section className="mianba-hero-copy" aria-labelledby="home-title">
          <p className="mianba-kicker mianba-reveal">Voice-first interview rehearsal</p>
          <h1 className="mianba-reveal" id="home-title">把每一次开口, 都练到能上场。</h1>
          <p className="mianba-lead mianba-reveal">
            面霸练习生把工作面试、研究生复试、考公面试和雅思口语拆成四套独立训练。你不用对着聊天框打字, 只需要听题、开口、被追问, 最后拿到能继续行动的复盘报告。
          </p>
          <div className="mianba-actions mianba-reveal">
            <a className="mianba-primary" href="/register">
              创建账号
              <AppIcon icon="lucide:arrow-right" size={18} />
            </a>
            <a className="mianba-secondary" href="#tracks">
              看训练模块
              <AppIcon icon="lucide:chevron-down" size={18} />
            </a>
          </div>
          <div className="mianba-hero-stats mianba-reveal">
            {heroStats.map(([value, suffix, label]) => (
              <div key={label}>
                <strong className="mianba-ticker" data-suffix={suffix} data-value={value}>
                  {value}
                  {suffix}
                </strong>
                <span>{label}</span>
              </div>
            ))}
          </div>
        </section>

        <section className={`mianba-cockpit is-${previewPhase}`} aria-label="语音面试训练台预览">
          <div className="mianba-cockpit-head">
            <span>
              <AppIcon icon="lucide:radio" size={17} />
              live practice room
            </span>
            <strong>{cockpitStatus}</strong>
          </div>
          <div className="mianba-cockpit-grid">
            <aside className="mianba-track-list" aria-label="训练模块预览">
              {trainingTracks.map((track, index) => (
                <button
                  aria-pressed={index === activeTrackIndex}
                  className={index === activeTrackIndex ? "is-active" : ""}
                  key={track.title}
                  onClick={() => {
                    setActiveTrackIndex(index);
                    setPreviewPhase("ready");
                  }}
                  type="button"
                >
                  <AppIcon icon={track.icon} size={18} />
                  <span>{track.title}</span>
                </button>
              ))}
            </aside>
            <div className="mianba-live-room">
              <div className="mianba-question-card">
                <span>{previewPhase === "followup" ? "AI 追问" : "面试官提问"}</span>
                <p>{previewQuestion}</p>
              </div>
              <div className="mianba-voice-core">
                <div className="mianba-core-ring">
                  <span className="mianba-core-pulse" />
                  <span className="mianba-core-orbit" />
                  <AppIcon icon="lucide:audio-lines" size={58} />
                </div>
                <div className="mianba-wave" aria-hidden="true">
                  {Array.from({ length: 19 }).map((_, index) => (
                    <span key={index} style={{ height: `${20 + (index % 8) * 8}px` }} />
                  ))}
                </div>
              </div>
              <div className="mianba-room-actions">
                <button
                  aria-pressed={previewPhase === "recording"}
                  className={previewPhase === "recording" ? "is-active" : ""}
                  onClick={() => setPreviewPhase("recording")}
                  type="button"
                >
                  <AppIcon icon="lucide:mic" size={18} />
                  开始回答
                </button>
                <button
                  aria-pressed={previewPhase === "followup"}
                  className={previewPhase === "followup" ? "is-active" : ""}
                  onClick={() => setPreviewPhase("followup")}
                  type="button"
                >
                  <AppIcon icon="lucide:square" size={18} />
                  回答完毕
                </button>
              </div>
            </div>
            <aside className="mianba-score-panel" aria-label="复盘指标预览">
              <span>{activeTrack.title}复盘快照</span>
              {activeTrack.preview.scores.map(([label, value]) => (
                <div key={label}>
                  <small>{label}</small>
                  <strong>{value}</strong>
                </div>
              ))}
            </aside>
          </div>
        </section>
      </header>

      <section className="mianba-proof-strip mianba-scroll-section">
        {trustItems.map(([icon, title, copy]) => (
          <article className="mianba-proof-card mianba-motion mianba-spotlight" key={title}>
            <AppIcon icon={icon} size={24} />
            <div>
              <h2>{title}</h2>
              <p>{copy}</p>
            </div>
          </article>
        ))}
      </section>

      <section className="mianba-section mianba-scroll-section" id="tracks">
        <div className="mianba-section-head mianba-motion">
          <p className="mianba-kicker">Four training tracks</p>
          <h2>不是泛泛聊天, 而是按目标拆开的四类训练。</h2>
          <p>每个模块都有独立材料入口、问题组织方式和报告维度, 让你在进入房间前就知道自己要练什么。</p>
        </div>
        <div className="mianba-track-grid">
          {trainingTracks.map((track, index) => (
            <article className="mianba-track-card mianba-motion mianba-spotlight" key={track.title}>
              <div className="mianba-card-top">
                <AppIcon icon={track.icon} size={28} />
                <span>0{index + 1}</span>
              </div>
              <h3>{track.title}</h3>
              <strong>{track.badge}</strong>
              <p>{track.description}</p>
              <ul>
                {track.checkpoints.map((item) => (
                  <li key={item}>
                    <AppIcon icon="lucide:check-circle-2" size={15} />
                    {item}
                  </li>
                ))}
              </ul>
              <em>{track.cost}</em>
            </article>
          ))}
        </div>
      </section>

      <section className="mianba-workflow mianba-scroll-section" id="workflow">
        <div className="mianba-section-head mianba-section-head-dark mianba-motion">
          <p className="mianba-kicker">Training rhythm</p>
          <h2>流程像真实面试, 但每一步都可复盘。</h2>
          <p>从选择目标到拿到报告, 页面只保留必要控制, 把注意力留给听题和表达。</p>
        </div>
        <div className="mianba-flow-shell">
          <span className="mianba-flow-beam" aria-hidden="true" />
          <div className="mianba-step-grid">
            {practiceSteps.map(([number, title, copy]) => (
              <article className="mianba-step-card mianba-motion mianba-spotlight" key={number}>
                <span>{number}</span>
                <h3>{title}</h3>
                <p>{copy}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="mianba-section mianba-scroll-section" id="reports">
        <div className="mianba-section-head mianba-motion">
          <p className="mianba-kicker">Actionable report</p>
          <h2>报告不只给分, 还告诉你下一轮该怎么练。</h2>
          <p>每一场训练都会留下问题、回答、追问、评分维度和改进建议, 方便你把一次练习变成下一次进步。</p>
        </div>
        <div className="mianba-report-grid">
          {reportCards.map((report) => (
            <article className="mianba-report-card mianba-motion mianba-spotlight" key={report.label}>
              <div className="mianba-report-head">
                <span>{report.label}</span>
                <strong
                  className="mianba-ticker"
                  data-decimals={report.score.includes(".") ? "1" : "0"}
                  data-value={report.score}
                >
                  {report.score}
                </strong>
              </div>
              <h3>{report.title}</h3>
              <div className="mianba-report-metrics">
                {report.metrics.map(([label, value]) => (
                  <div key={label}>
                    <span>{label}</span>
                    <meter max="100" min="0" value={value} />
                    <strong>{value}%</strong>
                  </div>
                ))}
              </div>
              <p className="mianba-report-summary">{report.summary}</p>
              <div className="mianba-report-detail-grid">
                <section>
                  <h4>评分依据</h4>
                  <ul>
                    {report.evidence.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                </section>
                <section>
                  <h4>下一轮动作</h4>
                  <ol>
                    {report.nextActions.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ol>
                </section>
              </div>
              <p>{report.advice}</p>
              <small className="mianba-report-reminder">{report.reminder}</small>
            </article>
          ))}
        </div>
      </section>

      <section className="mianba-credit-band mianba-scroll-section" id="credits">
        <div className="mianba-credit-copy mianba-motion">
          <p className="mianba-kicker">Credit activation</p>
          <h2>新人送 1 张体验券, 正式训练按场景扣次。</h2>
          <p>
            需要开通训练次数时, 可添加官方微信 Teptysuki666。确认后系统会发放对应模块次数, 并保留开通、扣次、训练和报告记录。
          </p>
          <div className="mianba-credit-notes" aria-label="开通与扣次说明">
            {creditNotes.map(([title, copy]) => (
              <article key={title}>
                <strong>{title}</strong>
                <p>{copy}</p>
              </article>
            ))}
          </div>
          <a className="mianba-primary" href="/register">
            先创建账号
            <AppIcon icon="lucide:arrow-right" size={18} />
          </a>
        </div>
        <div className="mianba-credit-table mianba-motion">
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

      <section className="mianba-final">
        <BrandLogo size={112} className="mianba-final-logo" />
        <h2>把紧张留在练习里, 把稳定带到真正上场时。</h2>
        <a className="mianba-final-action" href="/register">
          开始第一次模拟
          <AppIcon icon="lucide:arrow-right" size={18} />
        </a>
      </section>

      <footer className="mianba-footer">
        <div className="mianba-footer-brand">
          <BrandLogo size={48} />
          <span>面霸练习生</span>
        </div>
        <nav aria-label="底部链接">
          <a href="/terms">使用条款</a>
          <a href="/privacy">隐私政策</a>
          <a href="/refund">退款与纠纷</a>
          <a href="/contact">联系我们</a>
        </nav>
        <p>本产品用于面试和口语模拟训练, 报告仅作为复盘参考, 不承诺录取、上岸或考试成绩结果。</p>
        <div className="mianba-filing-links" aria-label="备案信息">
          <a href={filingLinks.icpUrl} target="_blank" rel="noreferrer">
            {filingLinks.icpNumber}
          </a>
          {filingLinks.policeNumber && (
            <a href={filingLinks.policeUrl} target="_blank" rel="noreferrer">
              {filingLinks.policeNumber}
            </a>
          )}
          <span>
            {filingLinks.policeNumber
              ? "备案信息以工信部和公安联网备案平台查询结果为准。"
              : "ICP备案信息以工信部备案管理系统查询结果为准。"}
          </span>
        </div>
        <small>© 2026 面霸练习生. All rights reserved.</small>
      </footer>
    </main>
  );
}
