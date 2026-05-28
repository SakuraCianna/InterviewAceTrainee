import { useLayoutEffect, useRef } from "react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { AppIcon } from "../components/AppIcon";

gsap.registerPlugin(ScrollTrigger);

const modules = [
  {
    icon: "lucide:briefcase-business",
    label: "工作面试",
    headline: "专业一面 + 专业二面 + HR 面",
    cost: "1 次 / 单场模拟",
    detail: "按求职链路逐轮推进，覆盖项目深挖、岗位匹配、沟通动机和临场追问。",
  },
  {
    icon: "lucide:graduation-cap",
    label: "研究生复试",
    headline: "一趟走完复试模拟",
    cost: "1 次 / 单场模拟",
    detail: "围绕自我介绍、专业基础、科研兴趣、毕业设计和导师沟通做完整问答。",
  },
  {
    icon: "lucide:landmark",
    label: "考公面试",
    headline: "结构化题型表达训练",
    cost: "1 次 / 单场模拟",
    detail: "训练审题、层次、公共视角和稳定输出，适配综合分析与应急应变。",
  },
  {
    icon: "lucide:languages",
    label: "雅思口语",
    headline: "Part 1 / 2 / 3 全流程",
    cost: "1 次 / 单场模拟",
    detail: "按口语考试节奏训练流利度、词汇、语法、发音和观点展开。",
  },
];

const flow = [
  ["01", "选择目标", "工作、复试、考公、雅思四类场景分别进入不同流程。"],
  ["02", "开场提问", "面试官播放问题，页面不放聊天框，避免依赖文字提示。"],
  ["03", "语音作答", "组织语言并说完后，手动点击“回答完毕”。"],
  ["04", "继续追问", "面试官根据上一轮回答继续深挖，形成真实压力感。"],
  ["05", "生成报告", "按场景输出评分、追问记录、短板和下一轮训练建议。"],
];

const reportMetrics = [
  ["表达稳定性", 88],
  ["逻辑结构", 82],
  ["场景匹配", 79],
  ["追问应对", 74],
  ["改进优先级", 91],
];

const practiceCoverage = [
  ["工作面试", "项目复盘、八股追问、岗位动机、HR 压力题"],
  ["研究生复试", "自我介绍、专业基础、科研兴趣、导师沟通"],
  ["考公面试", "综合分析、组织协调、人际沟通、应急应变"],
  ["雅思口语", "Part 1 日常问答、Part 2 话题卡、Part 3 延展讨论"],
];

const reportPreviews = [
  {
    label: "工作面试复盘",
    score: "86",
    title: "项目表达清晰，但二面追问还需要更稳。",
    metrics: [
      ["专业能力", 88],
      ["项目深挖", 81],
      ["岗位匹配", 84],
    ],
    followUps: ["这次架构取舍为什么优先保证吞吐？", "如果线上故障复现不了，下一步如何推进？"],
    advice: "下一轮重点练高并发项目里的取舍、指标和失败复盘。",
    evidence: "保留 3 轮问答、关键追问、评分维度和人工备注入口。",
    nextRound: "建议二面专项",
  },
  {
    label: "研究生复试复盘",
    score: "82",
    title: "科研兴趣完整，但专业基础回答略松散。",
    metrics: [
      ["专业基础", 76],
      ["科研潜力", 85],
      ["导师沟通", 83],
    ],
    followUps: ["选择这个研究方向的主要原因是什么？", "毕业设计里最核心的技术难点是什么？"],
    advice: "建议把毕设、论文阅读和研究方向整理成 90 秒表达。",
    evidence: "记录自我介绍、专业基础、科研方向和导师沟通四类表现。",
    nextRound: "建议基础补强",
  },
  {
    label: "雅思口语复盘",
    score: "6.5",
    title: "观点能展开，连接词和发音稳定性可继续提高。",
    metrics: [
      ["Fluency", 78],
      ["Vocabulary", 74],
      ["Grammar", 72],
    ],
    followUps: ["Can you give a specific example?", "How has this changed in recent years?"],
    advice: "Part 2 继续练故事结构，Part 3 增加对比和原因解释。",
    evidence: "保留 Part 1 / 2 / 3 关键回答、断点、可复用表达和弱项。",
    nextRound: "建议口语串联",
  },
];

const adminFeatures = [
  ["lucide:coins", "微信联系开通", "添加官方微信 Teptysuki666 后，可人工确认并发放对应训练次数。"],
  ["lucide:route", "稳定模型路由", "面试提问、语音识别和报告生成保留备用供应商，减少单一模型异常带来的中断。"],
  ["lucide:file-clock", "记录可追溯", "登录、开通、扣次、训练过程和报告生成都会保留记录，便于售后和争议核对。"],
  ["lucide:activity", "训练状态清晰", "账户剩余次数、训练完成情况和历史报告可持续留存，方便安排下一次练习。"],
];

export function HomePage() {
  const rootRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    if (!rootRef.current) {
      return;
    }

    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      return;
    }

    const context = gsap.context(() => {
      gsap.from(".home-nav", { y: -22, opacity: 0, duration: 0.7, ease: "power3.out" });
      gsap.from(".hero-intro > *", {
        y: 34,
        opacity: 0,
        duration: 0.9,
        ease: "power4.out",
        stagger: 0.07,
      });
      gsap.from(".console-shell", {
        y: 46,
        opacity: 0,
        rotateX: 4,
        duration: 1,
        delay: 0.15,
        ease: "power4.out",
      });
      gsap.to(".voice-meter span", {
        scaleY: 0.28,
        duration: 0.88,
        ease: "sine.inOut",
        stagger: 0.07,
        repeat: -1,
        yoyo: true,
      });
      gsap.utils.toArray<HTMLElement>(".motion-section").forEach((section) => {
        const items = Array.from(section.querySelectorAll<HTMLElement>(".motion-item"));
        if (items.length === 0) {
          return;
        }

        gsap.set(items, { autoAlpha: 0, y: 54 });
        gsap.to(items, {
          scrollTrigger: {
            trigger: section,
            start: "top 78%",
            toggleActions: "play none none none",
            once: true,
          },
          y: 0,
          autoAlpha: 1,
          duration: 0.82,
          clearProps: "transform,opacity,visibility",
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
    <main className="product-home" ref={rootRef}>
      <nav className="home-nav" aria-label="主导航">
        <a href="/" className="home-brand" aria-label="面霸练习生首页">
          <img src="/mianba-logo.svg" alt="" />
          <span>面霸练习生</span>
        </a>
        <div className="home-links">
          <a href="#modules">模块</a>
          <a href="#flow">流程</a>
          <a href="#report">报告</a>
          <a href="#admin">开通</a>
          <a href="/login">登录</a>
        </div>
      </nav>

      <header className="home-hero">
        <section className="hero-intro" aria-labelledby="home-title">
          <p className="home-eyebrow">Voice-first Interview Rehearsal</p>
          <h1 id="home-title">四类面试，按真实流程开练。</h1>
          <p className="home-lead">
            面霸练习生把工作面试、研究生复试、考公面试和雅思口语拆成独立训练产品。训练过程不打字、不看聊天框，只和数字面试官轮流语音问答，结束后拿到可复盘的完整报告。
          </p>
          <div className="home-actions">
            <a className="home-primary" href="/register">
              开始练习
              <AppIcon icon="lucide:arrow-right" size={18} />
            </a>
            <a className="home-secondary" href="#modules">
              查看模块
              <AppIcon icon="lucide:chevron-down" size={18} />
            </a>
          </div>
          <div className="hero-proof">
            <span><AppIcon icon="lucide:mic-2" size={16} />全程语音</span>
            <span><AppIcon icon="lucide:file-text" size={16} />完整报告</span>
            <span><AppIcon icon="lucide:shield-check" size={16} />记录可追溯</span>
          </div>
        </section>

        <section className="console-shell" aria-label="产品训练台预览">
          <div className="console-topbar">
            <div>
              <i />
              <i />
              <i />
            </div>
            <span>mianba.practice / live-room</span>
            <strong>Voice only</strong>
          </div>
          <div className="console-layout">
            <aside className="console-sidebar">
              <span className="console-label">训练模块</span>
              {modules.map((module) => (
                <button className={module.label === "工作面试" ? "is-active" : ""} key={module.label} type="button">
                  <AppIcon icon={module.icon} size={18} />
                  <span>{module.label}</span>
                </button>
              ))}
            </aside>
            <div className="live-panel">
              <div className="live-status">
                <span><AppIcon icon="lucide:radio" size={18} />面试官正在提问</span>
                <strong>第 1 / 3 轮</strong>
              </div>
              <div className="voice-board">
                <div className="voice-halo">
                  <AppIcon icon="lucide:audio-lines" size={58} />
                </div>
                <div className="voice-meter" aria-hidden="true">
                  {Array.from({ length: 18 }).map((_, index) => (
                    <span key={index} style={{ height: `${18 + (index % 7) * 9}px` }} />
                  ))}
                </div>
              </div>
              <div className="room-actions">
                <button type="button"><AppIcon icon="lucide:mic" size={18} />开始回答</button>
                <button type="button"><AppIcon icon="lucide:square" size={18} />回答完毕</button>
              </div>
            </div>
            <aside className="report-mini">
              <span className="console-label">本次复盘</span>
              {reportMetrics.slice(0, 4).map(([label, value]) => (
                <div className="mini-metric" key={label}>
                  <span>{label}</span>
                  <strong>{value}</strong>
                </div>
              ))}
            </aside>
          </div>
        </section>
      </header>

      <section className="home-section module-section motion-section" id="modules">
        <div className="section-heading motion-item">
          <p className="home-eyebrow">Four training products</p>
          <h2>四类目标, 对应不同训练深度。</h2>
          <p>首页直接呈现工作面试, 研究生复试, 考公面试和雅思口语四类训练, 可以快速判断本次该进入哪个场景。</p>
        </div>
        <div className="module-grid-v2">
          {modules.map((module, index) => (
            <article className="module-tile motion-item" key={module.label}>
              <div className="tile-index">0{index + 1}</div>
              <AppIcon icon={module.icon} size={30} />
              <h3>{module.label}</h3>
              <strong>{module.headline}</strong>
              <p>{module.detail}</p>
              <span>{module.cost}</span>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section coverage-section motion-section">
        <div className="section-heading motion-item">
          <p className="home-eyebrow">Practice coverage</p>
          <h2>进入训练前，先看到明确可练的题型。</h2>
          <p>每个模块都围绕真实考试或面试场景组织问题，不把四类目标都压进同一个泛面试入口。</p>
        </div>
        <div className="coverage-list">
          {practiceCoverage.map(([title, copy]) => (
            <article className="coverage-row motion-item" key={title}>
              <span>{title}</span>
              <p>{copy}</p>
              <AppIcon icon="lucide:arrow-right" size={18} />
            </article>
          ))}
        </div>
      </section>

      <section className="flow-band motion-section" id="flow">
        <div className="flow-heading motion-item">
          <p className="home-eyebrow">Voice-first workflow</p>
          <h2>按真实面试节奏逐轮推进。</h2>
        </div>
        <div className="flow-lane">
          {flow.map(([number, title, copy]) => (
            <article className="flow-card motion-item" key={number}>
              <span>{number}</span>
              <h3>{title}</h3>
              <p>{copy}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section report-deck motion-section" id="report">
        <div className="section-heading motion-item">
          <p className="home-eyebrow">Interview report</p>
          <h2>每一类训练，都给出一份能继续行动的复盘报告。</h2>
        </div>
        <div className="report-screens">
          {reportPreviews.map((report) => (
            <article className="report-screen motion-item" key={report.label}>
              <div className="screen-toolbar">
                <span>{report.label}</span>
                <strong>{report.score}</strong>
              </div>
              <h3>{report.title}</h3>
              <div className="screen-metrics">
                {report.metrics.map(([label, value]) => (
                  <div className="screen-metric" key={label}>
                    <span>{label}</span>
                    <meter max="100" min="0" value={Number(value)} />
                    <strong>{value}%</strong>
                  </div>
                ))}
              </div>
              <div className="screen-follow">
                <span><AppIcon icon="lucide:radio" size={14} />追问链路</span>
                {report.followUps.map((item) => (
                  <p key={item}>{item}</p>
                ))}
              </div>
              <div className="screen-advice">
                <span><AppIcon icon="lucide:file-text" size={14} />下一步训练</span>
                <p>{report.advice}</p>
              </div>
              <div className="screen-footnote">
                <span>{report.evidence}</span>
                <strong>{report.nextRound}</strong>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="admin-band motion-section" id="admin">
        <div className="admin-inner">
          <div className="section-heading motion-item">
            <p className="home-eyebrow">Credit activation</p>
            <h2>训练次数人工开通，流程清晰可追溯。</h2>
            <p>
              需要开通训练次数时，可添加官方微信 Teptysuki666。确认后，系统会发放对应模块次数，并保留开通、扣次、训练和报告记录。
            </p>
          </div>
          <div className="admin-feature-grid">
            {adminFeatures.map(([icon, title, copy]) => (
              <article className="admin-feature motion-item" key={title}>
                <AppIcon icon={icon} size={26} />
                <h3>{title}</h3>
                <p>{copy}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="home-section pricing-panel motion-section">
        <div className="pricing-copy motion-item">
          <p className="home-eyebrow">Scenario-based credits</p>
          <h2>一次训练消耗 1 次，四类场景任选。</h2>
          <p>新用户注册后默认获得 1 次免费面试机会，可在工作面试、研究生复试、考公面试和雅思口语中任选一类开始模拟。</p>
        </div>
        <div className="pricing-table motion-item">
          {modules.map((module) => (
            <div key={module.label}>
              <span><AppIcon icon={module.icon} size={18} />{module.label}</span>
              <strong>{module.cost}</strong>
            </div>
          ))}
        </div>
      </section>

      <section className="home-final">
        <img src="/mianba-logo.svg" alt="" />
        <h2>完成一次真实模拟, 下次上场就更稳。</h2>
        <a className="home-primary" href="/register">
          创建账户
          <AppIcon icon="lucide:arrow-right" size={18} />
        </a>
      </section>

      <footer className="home-footer">
        <div className="footer-brand">
          <img src="/mianba-logo.svg" alt="" />
          <span>面霸练习生</span>
        </div>
        <nav aria-label="底部链接">
          <a href="/terms">使用条款</a>
          <a href="/privacy">隐私政策</a>
          <a href="/refund">退款与纠纷</a>
          <a href="/contact">联系我们</a>
        </nav>
        <p>本产品用于面试和口语模拟训练，报告仅作为复盘参考，不承诺录取、上岸或考试成绩结果。</p>
        <small>© 2026 面霸练习生. All rights reserved.</small>
      </footer>
    </main>
  );
}
