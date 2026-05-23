import { useEffect, useRef } from "react";
import { ArrowRight, AudioLines, BriefcaseBusiness, Building2, GraduationCap, Languages, LockKeyhole, Radar, Route } from "lucide-react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { AvatarStage } from "../components/AvatarStage";

gsap.registerPlugin(ScrollTrigger);

const storyCards = [
  {
    eyebrow: "JOB",
    title: "工作面试",
    body: "完整体验包含专业一面、专业二面和 HR 面。既练技术深挖，也练岗位匹配、动机表达和压力追问。",
    icon: BriefcaseBusiness
  },
  {
    eyebrow: "POSTGRADUATE",
    title: "研究生复试面试",
    body: "一次模拟走完整场复试，覆盖自我介绍、专业基础、科研兴趣、毕业设计和导师沟通。",
    icon: GraduationCap
  },
  {
    eyebrow: "PUBLIC",
    title: "考公面试",
    body: "围绕结构化面试常见题型训练审题、层次、公共视角和语言稳定性。",
    icon: Building2
  },
  {
    eyebrow: "IELTS",
    title: "雅思口语面试",
    body: "从 Part 1 到 Part 3 模拟口语问答节奏，训练流利度、词汇、语法和观点展开。",
    icon: Languages
  }
];

const pipelineItems = [
  "选择面试模块",
  "匹配专属流程",
  "语音模拟问答",
  "按场景继续追问",
  "输出复盘报告"
];

const productModules = [
  {
    title: "工作面试",
    rounds: "专业一面 + 专业二面 + HR 面",
    cost: "3 次 / 完整体验"
  },
  {
    title: "研究生复试",
    rounds: "复试模拟一趟走完",
    cost: "1 次 / 单场"
  },
  {
    title: "考公面试",
    rounds: "结构化面试训练",
    cost: "1 次 / 单场"
  },
  {
    title: "雅思口语",
    rounds: "Part 1 + Part 2 + Part 3",
    cost: "2 次 / 口语全流程"
  }
];

export function HomePage() {
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!rootRef.current) {
      return;
    }

    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduceMotion) {
      return;
    }

    const context = gsap.context(() => {
      gsap.from(".nav-shell", { y: -28, opacity: 0, duration: 0.8, ease: "power3.out" });
      gsap.from(".hero-kicker, .hero-title span, .hero-lead, .hero-actions, .hero-note", {
        y: 42,
        opacity: 0,
        duration: 1,
        stagger: 0.08,
        ease: "power4.out"
      });
      gsap.from(".hero-product", {
        y: 70,
        rotateX: 8,
        opacity: 0,
        duration: 1.2,
        ease: "power4.out",
        delay: 0.18
      });
      gsap.to(".product-ring", {
        rotate: 360,
        duration: 18,
        repeat: -1,
        ease: "none"
      });
      gsap.to(".audio-thread", {
        xPercent: -45,
        duration: 18,
        repeat: -1,
        ease: "none"
      });

      gsap.utils.toArray<HTMLElement>(".story-card").forEach((card, index) => {
        gsap.from(card, {
          scrollTrigger: {
            trigger: card,
            start: "top 78%",
            end: "bottom 52%",
            scrub: 1
          },
          y: 80,
          opacity: 0,
          rotate: index % 2 === 0 ? -2 : 2,
          ease: "power2.out"
        });
      });

      gsap.from(".pipeline-item", {
        scrollTrigger: {
          trigger: ".pipeline-section",
          start: "top 70%",
          end: "bottom 65%",
          scrub: 1
        },
        x: -80,
        opacity: 0,
        stagger: 0.16,
        ease: "power2.out"
      });
      gsap.from(".module-card", {
        scrollTrigger: {
          trigger: ".module-grid",
          start: "top 76%",
          end: "bottom 68%",
          scrub: 1
        },
        y: 70,
        opacity: 0,
        stagger: 0.12,
        ease: "power2.out"
      });
    }, rootRef);

    return () => context.revert();
  }, []);

  return (
    <main className="site-shell" ref={rootRef}>
      <nav className="nav-shell" aria-label="主导航">
        <a href="/" className="brand-mark" aria-label="面霸练习生首页">
          <span className="brand-dot" />
          面霸练习生
        </a>
        <div className="nav-links">
          <a href="#experience">体验</a>
          <a href="#pipeline">流程</a>
          <a href="/login">登录</a>
        </div>
      </nav>

      <section className="hero-section">
        <div className="hero-copy">
          <p className="hero-kicker">AI Interview Rehearsal</p>
          <h1 className="hero-title">
            <span>把每一种面试</span>
            <span>提前排练到稳</span>
          </h1>
          <p className="hero-lead">
            面霸练习生把工作面试、研究生复试、考公面试和雅思口语做成可反复训练的 AI 面试场。不同场景匹配不同流程、轮次和报告标准。
          </p>
          <div className="hero-actions">
            <a className="primary-action" href="/register">
              开始练习
              <ArrowRight size={18} />
            </a>
            <a className="secondary-action" href="/login">已有账户登录</a>
          </div>
          <p className="hero-note">为求职、升学、考编和英语口语面试前的高强度练习设计。</p>
        </div>

        <div className="hero-product" aria-label="语音面试产品预览">
          <div className="product-ring" />
          <div className="audio-thread" aria-hidden="true">
            {Array.from({ length: 26 }).map((_, index) => (
              <span key={index} style={{ height: `${18 + (index % 7) * 9}px` }} />
            ))}
          </div>
          <div className="product-panel">
            <div className="session-strip">
              <span>Multi-Scenario Interview</span>
              <strong>4 Modules</strong>
            </div>
            <AvatarStage state="speaking" compact />
            <div className="prompt-card">
              <Radar size={18} />
              <p>请选择训练场景：工作面试、研究生复试、考公面试或雅思口语。</p>
            </div>
          </div>
        </div>
      </section>

      <section className="module-section" id="modules">
        <div className="section-heading">
          <span className="eyebrow">Interview Modules</span>
          <h2>四个场景，四套面试节奏</h2>
        </div>
        <div className="module-grid">
          {productModules.map((product) => (
            <article className="module-card" key={product.title}>
              <span>{product.title}</span>
              <h3>{product.rounds}</h3>
              <p>{product.cost}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="story-section" id="experience">
        {storyCards.map((card) => {
          const Icon = card.icon;
          return (
            <article className="story-card" key={card.title}>
              <div className="story-icon">
                <Icon size={28} />
              </div>
              <div>
                <span>{card.eyebrow}</span>
                <h2>{card.title}</h2>
                <p>{card.body}</p>
              </div>
            </article>
          );
        })}
      </section>

      <section className="pipeline-section" id="pipeline">
        <div className="section-heading">
          <span className="eyebrow">Interview Flow</span>
          <h2>一次练习，走完整个面试节奏</h2>
        </div>
        <div className="pipeline-track">
          {pipelineItems.map((item, index) => (
            <div className="pipeline-item" key={item}>
              <span>{String(index + 1).padStart(2, "0")}</span>
              <p>{item}</p>
            </div>
          ))}
        </div>
        <div className="closing-panel">
          <div>
            <LockKeyhole size={24} />
            <h2>账户、报告和后台都已准备好继续扩展</h2>
          </div>
          <a href="/register">
            创建账户
            <Route size={18} />
          </a>
        </div>
      </section>
    </main>
  );
}
