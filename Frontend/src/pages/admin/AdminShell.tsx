import { Activity, Bot, Coins, FileClock, Settings, Users } from "lucide-react";

const adminCards = [
  { icon: Users, title: "用户管理", value: "用户、状态、面试记录" },
  { icon: Coins, title: "次数账本", value: "发放、扣减、退款、修正" },
  { icon: Bot, title: "AI 供应商", value: "LLM / ASR / TTS 路由" },
  { icon: FileClock, title: "审计日志", value: "后台操作全量追踪" },
  { icon: Activity, title: "调用质量", value: "耗时、失败、成本估算" },
  { icon: Settings, title: "系统配置", value: "注册、面试轮数、后台入口" }
];

export function AdminShell() {
  return (
    <main className="workspace-page admin-page">
      <header className="workspace-header">
        <a href="/" className="brand-mark">
          <span className="brand-dot" />
          面霸练习生
        </a>
        <span className="session-pill session-pill--admin">Admin Console</span>
      </header>

      <section className="admin-hero">
        <span className="eyebrow">Operations</span>
        <h1>运营后台</h1>
        <p>管理员使用密码与邮箱验证码双重认证进入后台。所有敏感操作写入审计日志，支撑人工收费、退款和纠纷处理。</p>
      </section>

      <section className="admin-grid">
        {adminCards.map((card) => {
          const Icon = card.icon;
          return (
            <article className="admin-card" key={card.title}>
              <Icon size={24} />
              <h2>{card.title}</h2>
              <p>{card.value}</p>
            </article>
          );
        })}
      </section>
    </main>
  );
}

