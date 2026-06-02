import { useLayoutEffect, useRef } from "react";
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
};

type ReportCard = {
  label: string;
  score: string;
  title: string;
  metrics: Array<[string, number]>;
  advice: string;
};

const trainingTracks: TrainingTrack[] = [
  {
    icon: "lucide:briefcase-business",
    title: "工作面试",
    badge: "专业一面 / 二面 / HR 面",
    description: "围绕岗位要求、项目复盘、八股追问和沟通动机做连续深挖，适合正式面试前的高压预演。",
    checkpoints: ["岗位匹配", "项目细节", "压力追问"],
    cost: "2 次 / 深度模拟",
  },
  {
    icon: "lucide:graduation-cap",
    title: "研究生复试",
    badge: "院校专业复试",
    description: "把自我介绍、专业基础、科研兴趣、毕业设计和导师沟通拆成可练的问答链路。",
    checkpoints: ["专业基础", "科研表达", "导师沟通"],
    cost: "1 次 / 单场模拟",
  },
  {
    icon: "lucide:landmark",
    title: "考公面试",
    badge: "结构化题型训练",
    description: "训练审题、层次、公共视角和稳定输出，覆盖综合分析、组织协调、人际沟通和应急应变。",
    checkpoints: ["综合分析", "组织协调", "应急应变"],
    cost: "1 次 / 单场模拟",
  },
  {
    icon: "lucide:languages",
    title: "雅思口语",
    badge: "Part 1 / 2 / 3",
    description: "按真实口语节奏练流利度、词汇、语法、发音和观点展开，结束后给出可复用表达。",
    checkpoints: ["流利度", "话题卡", "观点展开"],
    cost: "2 次 / 完整口语",
  },
];

const heroStats = [
  ["250+", "岗位方向"],
  ["100+", "复试专业"],
  ["4", "训练产品"],
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
    advice: "Part 2 继续练故事结构，Part 3 增加对比和原因解释。",
  },
];

const trustItems = [
  ["lucide:mic-2", "语音优先", "从听题到回答都按真实开口节奏推进。"],
  ["lucide:file-text", "复盘可追", "保留问题、回答、追问和报告维度。"],
  ["lucide:shield-check", "边界清楚", "报告用于训练复盘，不承诺录取或考试结果。"],
];

export function HomePage() {
  const rootRef = useRef<HTMLDivElement>(null);

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
    <main className="mianba-home" ref={rootRef}>
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
            {heroStats.map(([value, label]) => (
              <div key={label}>
                <strong>{value}</strong>
                <span>{label}</span>
              </div>
            ))}
          </div>
        </section>

        <section className="mianba-cockpit" aria-label="语音面试训练台预览">
          <div className="mianba-cockpit-head">
            <span>
              <AppIcon icon="lucide:radio" size={17} />
              live practice room
            </span>
            <strong>第 1 / 3 轮</strong>
          </div>
          <div className="mianba-cockpit-grid">
            <aside className="mianba-track-list" aria-label="训练模块预览">
              {trainingTracks.map((track, index) => (
                <button className={index === 0 ? "is-active" : ""} key={track.title} type="button">
                  <AppIcon icon={track.icon} size={18} />
                  <span>{track.title}</span>
                </button>
              ))}
            </aside>
            <div className="mianba-live-room">
              <div className="mianba-question-card">
                <span>面试官提问</span>
                <p>请用一个项目说明, 你如何在约束条件下做技术取舍?</p>
              </div>
              <div className="mianba-voice-core">
                <div className="mianba-core-ring">
                  <AppIcon icon="lucide:audio-lines" size={58} />
                </div>
                <div className="mianba-wave" aria-hidden="true">
                  {Array.from({ length: 19 }).map((_, index) => (
                    <span key={index} style={{ height: `${20 + (index % 8) * 8}px` }} />
                  ))}
                </div>
              </div>
              <div className="mianba-room-actions">
                <button type="button">
                  <AppIcon icon="lucide:mic" size={18} />
                  开始回答
                </button>
                <button type="button">
                  <AppIcon icon="lucide:square" size={18} />
                  回答完毕
                </button>
              </div>
            </div>
            <aside className="mianba-score-panel" aria-label="复盘指标预览">
              <span>复盘快照</span>
              {reportCards[0].metrics.map(([label, value]) => (
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
          <article className="mianba-proof-card mianba-motion" key={title}>
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
            <article className="mianba-track-card mianba-motion" key={track.title}>
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
        <div className="mianba-step-grid">
          {practiceSteps.map(([number, title, copy]) => (
            <article className="mianba-step-card mianba-motion" key={number}>
              <span>{number}</span>
              <h3>{title}</h3>
              <p>{copy}</p>
            </article>
          ))}
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
            <article className="mianba-report-card mianba-motion" key={report.label}>
              <div className="mianba-report-head">
                <span>{report.label}</span>
                <strong>{report.score}</strong>
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
              <p>{report.advice}</p>
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
        <BrandLogo size={54} />
        <h2>把紧张留在练习里, 把稳定带到真正上场时。</h2>
        <a className="mianba-final-action" href="/register">
          开始第一次模拟
          <AppIcon icon="lucide:arrow-right" size={18} />
        </a>
      </section>

      <footer className="mianba-footer">
        <div className="mianba-footer-brand">
          <BrandLogo size={34} />
          <span>面霸练习生</span>
        </div>
        <nav aria-label="底部链接">
          <a href="/terms">使用条款</a>
          <a href="/privacy">隐私政策</a>
          <a href="/refund">退款与纠纷</a>
          <a href="/contact">联系我们</a>
        </nav>
        <p>本产品用于面试和口语模拟训练, 报告仅作为复盘参考, 不承诺录取、上岸或考试成绩结果。</p>
        <small>© 2026 面霸练习生. All rights reserved.</small>
      </footer>
    </main>
  );
}
