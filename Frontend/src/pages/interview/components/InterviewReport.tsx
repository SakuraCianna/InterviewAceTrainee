import { AppIcon } from "../../../components/AppIcon";
import type { InterviewReport as InterviewReportData } from "../types";
import styles from "./InterviewReport.module.css";

const REPORT_COPY = {
  default: {
    lang: "zh-CN",
    score: "综合得分",
    fallbackTitle: "本轮训练复盘",
    strengths: "本轮亮点",
    improvements: "需要强化",
    dimensions: "能力维度",
    evidenceCount: (count: number) => `${count} 项证据评分`,
    evidence: "评分依据",
    risks: "风险提醒",
    priorityActions: "优先动作",
    nextPlan: "下一轮建议",
    drills: "推荐练习",
    turns: "问答记录",
    emptyInsight: "暂无需要特别说明的内容",
    emptyDimensions: "暂无能力维度评分",
    emptyPlan: "暂无待执行计划",
    emptyTurns: "暂无问答记录",
  },
  ielts: {
    lang: "en",
    score: "Overall Score",
    fallbackTitle: "IELTS Speaking Review",
    strengths: "Strengths",
    improvements: "Areas to Improve",
    dimensions: "Assessment Criteria",
    evidenceCount: (count: number) => `${count} evidence-based ${count === 1 ? "rating" : "ratings"}`,
    evidence: "Evidence",
    risks: "Risk Alerts",
    priorityActions: "Priority Actions",
    nextPlan: "Next Practice Plan",
    drills: "Recommended Drills",
    turns: "Q&A Transcript",
    emptyInsight: "Nothing specific to highlight.",
    emptyDimensions: "No assessment criteria are available.",
    emptyPlan: "No action has been scheduled.",
    emptyTurns: "No Q&A transcript is available.",
  },
} as const;

export function InterviewReport({ report }: { report: InterviewReportData }) {
  const copy = report.interview_type === "ielts" ? REPORT_COPY.ielts : REPORT_COPY.default;

  return (
    <section className={styles.report} aria-labelledby="interview-report-title" lang={copy.lang}>
      <header className={styles.hero}>
        <div className={styles.score}>
          <strong>{report.total_score}</strong>
          <span>{copy.score}</span>
        </div>
        <div>
          <span className={styles.kicker}>TRAINING REPORT</span>
          <h2 id="interview-report-title">{report.readiness_level || copy.fallbackTitle}</h2>
          <p>{report.summary}</p>
        </div>
      </header>

      {report.score_explanation && (
        <div className={styles.explanation}>
          <AppIcon icon="lucide:gauge" size={18} />
          <span>{report.score_explanation}</span>
        </div>
      )}

      <div className={styles.twoColumns}>
        <Insight title={copy.strengths} icon="lucide:check-circle-2" items={report.strengths} emptyText={copy.emptyInsight} tone="positive" />
        <Insight title={copy.improvements} icon="lucide:activity" items={report.improvements} emptyText={copy.emptyInsight} tone="warning" />
      </div>

      <section className={styles.section} aria-labelledby="dimension-title">
        <div className={styles.sectionHeading}>
          <h3 id="dimension-title">{copy.dimensions}</h3>
          <span>{copy.evidenceCount(report.dimensions.length)}</span>
        </div>
        <div className={styles.dimensions}>
          {report.dimensions.length === 0 && <p>{copy.emptyDimensions}</p>}
          {report.dimensions.map((dimension) => (
            <article key={dimension.name}>
              <div className={styles.dimensionHeading}>
                <span>{dimension.name}</span>
                <strong>{dimension.score}</strong>
              </div>
              {dimension.level && <small>{dimension.level}</small>}
              <p>{dimension.comment}</p>
              {dimension.evidence?.map((item) => <em key={item}>{item}</em>)}
              {dimension.action && <b>{dimension.action}</b>}
            </article>
          ))}
        </div>
      </section>

      <div className={styles.twoColumns}>
        <Insight title={copy.evidence} icon="lucide:list-checks" items={report.evidence || []} emptyText={copy.emptyInsight} />
        <Insight title={copy.risks} icon="lucide:triangle-alert" items={report.risk_flags || []} emptyText={copy.emptyInsight} tone="warning" />
      </div>

      <div className={styles.plans}>
        <Plan title={copy.priorityActions} items={report.priority_actions || []} emptyText={copy.emptyPlan} />
        <Plan title={copy.nextPlan} items={report.next_plan} emptyText={copy.emptyPlan} />
      </div>

      {(report.recommended_drills || []).length > 0 && (
        <div className={styles.drills} aria-label={copy.drills}>
          {report.recommended_drills.map((item) => <span key={item}>{item}</span>)}
        </div>
      )}

      <section className={styles.turns} aria-labelledby="turns-title">
        <h3 id="turns-title"><AppIcon icon="lucide:file-text" size={17} />{copy.turns}</h3>
        {report.turns.length === 0 && <p>{copy.emptyTurns}</p>}
        {report.turns.map((turn, index) => (
          <details key={`${turn.round_name}-${index}`}>
            <summary>
              <span>{turn.round_name}</span>
              {typeof turn.score === "number" && <b>{turn.score}</b>}
            </summary>
            <p>{turn.question}</p>
            <blockquote>{turn.answer}</blockquote>
            {turn.feedback && <p>{turn.feedback}</p>}
            {turn.evidence && turn.evidence.length > 0 && (
              <div className={styles.tags}>{turn.evidence.map((item) => <span key={item}>{item}</span>)}</div>
            )}
          </details>
        ))}
      </section>
    </section>
  );
}

function Insight({
  title,
  icon,
  items,
  emptyText,
  tone = "neutral",
}: {
  title: string;
  icon: string;
  items: string[];
  emptyText: string;
  tone?: "neutral" | "positive" | "warning";
}) {
  return (
    <article className={`${styles.insight} ${styles[tone]}`}>
      <h3><AppIcon icon={icon} size={17} />{title}</h3>
      {items.length > 0 ? items.map((item) => <span key={item}>{item}</span>) : <span>{emptyText}</span>}
    </article>
  );
}

function Plan({ title, items, emptyText }: { title: string; items: string[]; emptyText: string }) {
  return (
    <section>
      <h3>{title}</h3>
      {items.length === 0 && <p>{emptyText}</p>}
      {items.map((item, index) => <p key={item}><b>{index + 1}</b>{item}</p>)}
    </section>
  );
}
