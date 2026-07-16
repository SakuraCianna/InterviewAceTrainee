package icu.sakuracianna.mianba.aiwork.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import icu.sakuracianna.mianba.interview.packageflow.JobInterviewStage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiJobWorkerReportTest {
    private static final String NEUTRAL_CHINESE_FEEDBACK =
            "该段评价超出训练反馈边界，请仅依据可观察证据继续练习。";
    private static final String NEUTRAL_ENGLISH_FEEDBACK =
            "This evaluation exceeds the practice-feedback boundary; "
                    + "focus on observable evidence and actionable improvement.";

    @Test
    void assemblesSessionReportFromEveryTurnAndAggregatesStableDimensions() throws Exception {
        UUID sessionId = UUID.randomUUID();
        InterviewReportAssembler assembler = new InterviewReportAssembler();
        List<InterviewReportAssembler.EvaluatedTurn> turns = List.of(
                turn(1, "岗位能力", "BEHAVIORAL", 70, "结论可以更聚焦",
                        dimension("SYSTEM_DESIGN", 70, "解释了缓存取舍", "补充容量依据"),
                        dimension("COMMUNICATION", 60, "结论在末尾给出", "重点需要前置")),
                turn(0, "基础核验", "KNOWLEDGE", 90, "证据充分",
                        dimension("SYSTEM_DESIGN", 90, "说明了失效策略", "取舍清晰")));

        InterviewReportAssembler.ReportDraft report = assembler.assembleSession(
                new InterviewReportAssembler.SessionReportInput(
                        sessionId, "job", "TECHNICAL_FIRST", true,
                        "job-turn-v2", "job-package-rubric-v1", "turn-evaluation-v2",
                        true, turns));

        assertThat(report.totalScore()).isEqualTo(80);
        assertThat(report.body())
                .containsEntry("report_scope", "SESSION")
                .containsEntry("session_id", sessionId.toString())
                .containsEntry("generation_status", "ENHANCING")
                .containsEntry("summary_source", "TEMPLATE")
                .containsEntry("current_revision", 1)
                .containsEntry("prompt_version", "job-turn-v2")
                .containsEntry("rubric_version", "job-package-rubric-v1")
                .containsEntry("output_schema_version", "turn-evaluation-v2")
                .containsEntry("material_context_applied", true);
        assertThat(report.body().get("summary").toString()).contains("本场完成 2 轮", "80 分");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dimensions =
                (List<Map<String, Object>>) report.body().get("dimensions");
        assertThat(dimensions).extracting(item -> item.get("code"))
                .containsExactly("COMMUNICATION", "SYSTEM_DESIGN");
        assertThat(dimensions).extracting(item -> item.get("score"))
                .containsExactly(60, 80);
        assertThat(dimensions.get(1).get("evidence").toString())
                .contains("说明了失效策略", "解释了缓存取舍");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orderedTurns =
                (List<Map<String, Object>>) report.body().get("turns");
        assertThat(orderedTurns).extracting(item -> item.get("turn_index"))
                .containsExactly(0, 1);

        List<InterviewReportAssembler.EvaluatedTurn> reversed = new ArrayList<>(turns);
        java.util.Collections.reverse(reversed);
        Map<String, Object> replay = assembler.assembleSession(
                new InterviewReportAssembler.SessionReportInput(
                        sessionId, "job", "TECHNICAL_FIRST", true,
                        "job-turn-v2", "job-package-rubric-v1", "turn-evaluation-v2",
                        true, reversed)).body();
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.writeValueAsString(replay))
                .isEqualTo(mapper.writeValueAsString(report.body()));
    }

    @Test
    void ieltsReportUsesEnglishForEveryServerGeneratedVisibleField() {
        InterviewReportAssembler assembler = new InterviewReportAssembler();
        List<InterviewReportAssembler.EvaluatedTurn> turns = List.of(
                englishTurn(0, "被污染的中文轮次", 82,
                        "The answer is clear and relevant, but it needs a more specific example."),
                englishTurn(1, "另一个中文轮次", 76,
                        "The response is coherent; vary the sentence structures and add detail."));

        Map<String, Object> report = assembler.assembleSession(
                new InterviewReportAssembler.SessionReportInput(
                        UUID.randomUUID(), "ielts", "IELTS_SPEAKING", false,
                        "ielts-v2", "ielts-rubric-v2", "turn-evaluation-v2",
                        false, turns)).body();

        assertThat(report)
                .containsEntry("generation_status", "BASE_READY")
                .containsEntry("readiness_level", "Mostly ready");
        assertThat(report.get("score_explanation").toString())
                .contains("rounded arithmetic mean", "practice guidance only");
        assertThat(report.get("summary").toString()).contains("overall average score");
        assertThat(report.get("next_plan").toString()).contains("Rewrite", "timed");
        assertThat(report.get("recommended_drills").toString())
                .contains("IELTS", "speaking");
        assertThat(report.get("turns").toString())
                .contains("Part 1 · Introduction", "Part 1 · Familiar Topics");
        assertThat(report.toString()).doesNotContainPattern("[\\p{IsHan}]");
    }

    @Test
    void jobAlgorithmReportIsChineseAndDisclaimsCodeExecution() {
        InterviewReportAssembler assembler = new InterviewReportAssembler();
        InterviewReportAssembler.EvaluatedTurn algorithm = turn(
                0, "算法思路", "ALGORITHM_REASONING", 68,
                "思路方向正确，但边界条件不完整。",
                dimension("PROBLEM_SOLVING", 68, "说明了双指针步骤", "补充空输入边界"));

        Map<String, Object> report = assembler.assembleSession(
                new InterviewReportAssembler.SessionReportInput(
                        UUID.randomUUID(), "job", "TECHNICAL_FIRST", false,
                        "job-v2", "job-rubric-v1", "turn-evaluation-v2",
                        false, List.of(algorithm))).body();

        assertThat(report)
                .containsEntry("generation_status", "BASE_READY")
                .containsEntry("readiness_level", "需要加强")
                .containsEntry("algorithm_assessment_mode", "SPOKEN_REASONING_ONLY");
        assertThat(report.get("score_explanation").toString()).contains("算术平均值", "训练参考");
        assertThat(report.get("summary").toString()).contains("本场完成", "优先改进");
        assertThat(report.get("next_plan").toString()).contains("重写", "限时复述");
        assertThat(report.get("recommended_drills").toString()).contains("STAR", "限时表达");
        assertThat(report.get("assessment_notice").toString())
                .isEqualTo("算法题仅评价口述思路、复杂度与边界，未执行或编译代码，也未进行在线判题。");
        assertThat(report.get("assessment_notice").toString())
                .doesNotContain("通过测试", "AC", "录用");
        assertThat(report.toString())
                .doesNotContain("建议录用", "考试通过", "薪酬承诺", "代码通过测试")
                .doesNotContainPattern("\\bAC\\b");
    }

    @Test
    void packageReportAggregatesExactlyThreeStagesWithoutCopyingTurns() {
        InterviewReportAssembler assembler = new InterviewReportAssembler();
        UUID packageId = UUID.randomUUID();
        List<InterviewReportAssembler.StageSummary> stages = List.of(
                stage(JobInterviewStage.HR_FINAL, 70,
                        dimension("COMMUNICATION", 70, "说明了冲突处理", "结果可以量化")),
                stage(JobInterviewStage.TECHNICAL_FIRST, 80,
                        dimension("SYSTEM_DESIGN", 80, "说明了缓存策略", "补充容量估算")),
                stage(JobInterviewStage.TECHNICAL_SECOND, 90,
                        dimension("SYSTEM_DESIGN", 90, "比较了两种架构", "取舍依据充分")));

        Map<String, Object> report = assembler.assemblePackage(
                new InterviewReportAssembler.PackageReportInput(
                        packageId, true, "package-v1", "job-rubric-v1",
                        "package-report-v1", stages)).body();

        assertThat(report)
                .containsEntry("report_scope", "PACKAGE")
                .containsEntry("package_id", packageId.toString())
                .containsEntry("interview_type", "job")
                .containsEntry("total_score", 80)
                .containsEntry("generation_status", "ENHANCING")
                .doesNotContainKeys("turns", "question", "answer");
        assertThat(report.get("summary").toString()).contains("三场", "80 分");
        assertThat(report.get("stages").toString())
                .containsSubsequence("TECHNICAL_FIRST", "TECHNICAL_SECOND", "HR_FINAL")
                .doesNotContain("question", "answer");
        assertThat(report.get("dimensions").toString())
                .contains("SYSTEM_DESIGN", "85", "COMMUNICATION", "70");
    }

    @Test
    void jobSessionNeutralizesChineseDecisionsAndPreservesSafeTrainingText() {
        InterviewReportAssembler assembler = new InterviewReportAssembler();
        InterviewReportAssembler.EvaluatedTurn turn = new InterviewReportAssembler.EvaluatedTurn(
                0,
                "综合核验",
                "请说明一次协作经历。",
                "我先澄清目标，再协调分工。",
                "BEHAVIORAL",
                60,
                "候选人已通过本轮面试，建议聘用。",
                List.of(
                        dimension(
                                "COMMUNICATION",
                                40,
                                "该回答足以证明考试通过，可以发放 Offer。",
                                "建议录用候选人，并承诺薪酬不低于三万元。"),
                        dimension(
                                "SYSTEM_DESIGN",
                                80,
                                "通过量化结果说明缓存命中率提升。",
                                "薪资期望表达清楚，系统设计取舍具体。")),
                List.of("BEHAVIORAL"),
                List.of("CROSS_TEAM_COLLABORATION"),
                List.of());

        Map<String, Object> report = assembler.assembleSession(
                new InterviewReportAssembler.SessionReportInput(
                        UUID.randomUUID(), "job", "HR_FINAL", false,
                        "job-v2", "job-rubric-v1", "turn-evaluation-v2",
                        false, List.of(turn))).body();

        assertThat(report.toString())
                .contains(NEUTRAL_CHINESE_FEEDBACK)
                .contains("通过量化结果说明缓存命中率提升。")
                .contains("薪资期望表达清楚，系统设计取舍具体。")
                .doesNotContain(
                        "建议聘用", "考试通过", "发放 Offer", "建议录用", "承诺薪酬");
        assertThat(report.get("summary").toString()).contains(NEUTRAL_CHINESE_FEEDBACK);
        assertThat(report.get("strengths").toString())
                .contains("薪资期望表达清楚，系统设计取舍具体。");
        assertThat(report.get("improvements").toString()).contains(NEUTRAL_CHINESE_FEEDBACK);
        assertThat(report.get("priority_actions").toString()).contains(NEUTRAL_CHINESE_FEEDBACK);
        assertThat(report.get("dimensions").toString())
                .contains(NEUTRAL_CHINESE_FEEDBACK)
                .contains("通过量化结果说明缓存命中率提升。");
        assertThat(report.get("turns").toString())
                .contains("feedback=" + NEUTRAL_CHINESE_FEEDBACK);
    }

    @Test
    void ieltsSessionNeutralizesEnglishDecisionsWithoutProducingChineseCopy() {
        InterviewReportAssembler assembler = new InterviewReportAssembler();
        InterviewReportAssembler.EvaluatedTurn turn = new InterviewReportAssembler.EvaluatedTurn(
                0,
                "untrusted stage name",
                "What makes a good team member?",
                "A good team member communicates clearly and respects different views.",
                "IELTS_SPEAKING",
                72,
                "Hire the candidate and extend a job offer.",
                List.of(
                        dimension(
                                "COHERENCE",
                                60,
                                "The candidate passed the interview.",
                                "A salary commitment is guaranteed."),
                        dimension(
                                "FLUENCY",
                                84,
                                "The response can pass data through a clear sequence.",
                                "Offer a specific example to improve clarity.")),
                List.of("PART_ONE"),
                List.of("TEAMWORK"),
                List.of());

        Map<String, Object> report = assembler.assembleSession(
                new InterviewReportAssembler.SessionReportInput(
                        UUID.randomUUID(), "ielts", "IELTS_SPEAKING", false,
                        "ielts-v2", "ielts-rubric-v2", "turn-evaluation-v2",
                        false, List.of(turn))).body();

        assertThat(report.toString())
                .contains(NEUTRAL_ENGLISH_FEEDBACK)
                .contains("The response can pass data through a clear sequence.")
                .contains("Offer a specific example to improve clarity.")
                .doesNotContain(
                        "Hire the candidate", "job offer", "passed the interview",
                        "salary commitment")
                .doesNotContainPattern("[\\p{IsHan}]");
        assertThat(report.get("summary").toString()).contains(NEUTRAL_ENGLISH_FEEDBACK);
        assertThat(report.get("turns").toString())
                .contains("feedback=" + NEUTRAL_ENGLISH_FEEDBACK);
    }

    @Test
    void offerOutcomePatternsDistinguishHiringDecisionsFromCandidateDiscussion() {
        InterviewReportAssembler assembler = new InterviewReportAssembler();
        InterviewReportAssembler.EvaluatedTurn turn = new InterviewReportAssembler.EvaluatedTurn(
                0,
                "untrusted stage name",
                "How would you compare two career opportunities?",
                "I would compare the role, growth path, and long-term fit.",
                "IELTS_SPEAKING",
                70,
                "We will offer the candidate the position.",
                List.of(
                        dimension(
                                "COHERENCE",
                                60,
                                "Offer the applicant a role after this interview.",
                                "Offer a specific example to improve clarity."),
                        dimension(
                                "FLUENCY",
                                80,
                                "Compare this job offer with your long-term priorities.",
                                "Discuss how you would evaluate a job offer.")),
                List.of("PART_ONE"),
                List.of("CAREER_CHOICES"),
                List.of());

        Map<String, Object> report = assembler.assembleSession(
                new InterviewReportAssembler.SessionReportInput(
                        UUID.randomUUID(), "ielts", "IELTS_SPEAKING", false,
                        "ielts-v2", "ielts-rubric-v2", "turn-evaluation-v2",
                        false, List.of(turn))).body();

        assertThat(report.toString())
                .contains(NEUTRAL_ENGLISH_FEEDBACK)
                .contains("Offer a specific example to improve clarity.")
                .contains("Compare this job offer with your long-term priorities.")
                .contains("Discuss how you would evaluate a job offer.")
                .doesNotContain(
                        "We will offer the candidate the position.",
                        "Offer the applicant a role after this interview.");
    }

    @Test
    void packageNeutralizesChineseAndEnglishDecisionsAcrossDerivedFields() {
        InterviewReportAssembler assembler = new InterviewReportAssembler();
        List<InterviewReportAssembler.StageSummary> stages = List.of(
                stage(JobInterviewStage.TECHNICAL_FIRST, 45,
                        dimension(
                                "COMMUNICATION", 45,
                                "建议直接录用，并保证年薪不低于目标值。",
                                "候选人面试通过，可以聘用。")),
                stage(JobInterviewStage.TECHNICAL_SECOND, 65,
                        dimension(
                                "SYSTEM_DESIGN", 65,
                                "Recommend hiring the applicant.",
                                "The applicant has passed the assessment; salary is guaranteed.")),
                stage(JobInterviewStage.HR_FINAL, 85,
                        dimension(
                                "MOTIVATION", 85,
                                "通过量化结果说明求职动机与岗位匹配。",
                                "薪资期望表达清晰，没有作出金额承诺。")));

        Map<String, Object> report = assembler.assemblePackage(
                new InterviewReportAssembler.PackageReportInput(
                        UUID.randomUUID(), false, "package-v1", "job-rubric-v1",
                        "package-report-v1", stages)).body();

        assertThat(report.toString())
                .contains(NEUTRAL_CHINESE_FEEDBACK)
                .contains("通过量化结果说明求职动机与岗位匹配。")
                .contains("薪资期望表达清晰，没有作出金额承诺。")
                .doesNotContain(
                        "建议直接录用", "保证年薪", "面试通过", "可以聘用",
                        "Recommend hiring", "passed the assessment",
                        "salary is guaranteed");
        assertThat(report.get("summary").toString()).contains(NEUTRAL_CHINESE_FEEDBACK);
        assertThat(report.get("dimensions").toString()).contains(NEUTRAL_CHINESE_FEEDBACK);
        assertThat(report.get("strengths").toString())
                .contains("薪资期望表达清晰，没有作出金额承诺。");
        assertThat(report.get("improvements").toString()).contains(NEUTRAL_CHINESE_FEEDBACK);
        assertThat(report.get("priority_actions").toString())
                .contains(NEUTRAL_CHINESE_FEEDBACK);
    }

    @Test
    void rejectsEmptyDuplicateInvalidOrNullSessionInputs() {
        InterviewReportAssembler assembler = new InterviewReportAssembler();
        UUID sessionId = UUID.randomUUID();

        assertThatThrownBy(() -> assembler.assembleSession(
                new InterviewReportAssembler.SessionReportInput(
                        sessionId, "job", "TECHNICAL_FIRST", false,
                        "p", "r", "s", false, List.of())))
                .isInstanceOf(IllegalArgumentException.class);

        InterviewReportAssembler.EvaluatedTurn first = turn(
                0, "基础", "KNOWLEDGE", 80, "证据充分",
                dimension("FOUNDATIONS", 80, "解释了机制", "可以补充边界"));
        InterviewReportAssembler.EvaluatedTurn duplicateIndex = turn(
                0, "项目", "PROJECT", 70, "缺少指标",
                dimension("PROJECT_DEPTH", 70, "描述了行动", "补充结果"));
        assertThatThrownBy(() -> assembler.assembleSession(
                new InterviewReportAssembler.SessionReportInput(
                        sessionId, "job", "TECHNICAL_FIRST", false,
                        "p", "r", "s", false, List.of(first, duplicateIndex))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("turn index");

        assertThatThrownBy(() -> turn(
                1, "基础", "KNOWLEDGE", 101, "非法分数",
                dimension("FOUNDATIONS", 80, "证据", "建议")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> turn(
                1, "基础", "KNOWLEDGE", 80, "证据充分",
                dimension("FOUNDATIONS", 80, "证据", "建议"),
                dimension("FOUNDATIONS", 70, "另一证据", "另一建议")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension code");
        assertThatThrownBy(() -> new InterviewReportAssembler.SessionReportInput(
                sessionId, "job", "TECHNICAL_FIRST", false,
                "p", "r", "s", false, Arrays.asList(first, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportInputsAndOutputsAreImmutableAndPackageRequiresFixedStageSet() {
        InterviewReportAssembler assembler = new InterviewReportAssembler();
        List<InterviewReportAssembler.EvaluatedTurn> mutableTurns = new ArrayList<>();
        mutableTurns.add(turn(0, "基础", "KNOWLEDGE", 80, "证据充分",
                dimension("FOUNDATIONS", 80, "解释了机制", "补充边界")));
        InterviewReportAssembler.SessionReportInput input =
                new InterviewReportAssembler.SessionReportInput(
                        UUID.randomUUID(), "job", "TECHNICAL_FIRST", false,
                        "p", "r", "s", false, mutableTurns);
        mutableTurns.clear();

        Map<String, Object> body = assembler.assembleSession(input).body();
        assertThat(input.turns()).hasSize(1).isUnmodifiable();
        assertThat(body).isUnmodifiable();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dimensions =
                (List<Map<String, Object>>) body.get("dimensions");
        assertThat(dimensions).isUnmodifiable();
        assertThat(dimensions.getFirst()).isUnmodifiable();

        assertThatThrownBy(() -> assembler.assemblePackage(
                new InterviewReportAssembler.PackageReportInput(
                        UUID.randomUUID(), false, "p", "r", "s", List.of(
                                stage(JobInterviewStage.TECHNICAL_FIRST, 80,
                                        dimension("FOUNDATIONS", 80, "证据", "建议")),
                                stage(JobInterviewStage.TECHNICAL_SECOND, 80,
                                        dimension("SYSTEM_DESIGN", 80, "证据", "建议"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three stages");
    }

    private static InterviewReportAssembler.EvaluatedTurn englishTurn(
            int index, String stageName, int score, String feedback) {
        return new InterviewReportAssembler.EvaluatedTurn(
                index,
                stageName,
                "What would you change in your daily routine?",
                "I would exercise before work because it helps me focus.",
                "IELTS_SPEAKING",
                score,
                feedback,
                List.of(dimension(
                        "FLUENCY", score, "The answer develops one relevant idea.",
                        "Add a more specific example.")),
                List.of("PART_ONE"),
                List.of("DAILY_ROUTINE"),
                List.of());
    }

    private static InterviewReportAssembler.EvaluatedTurn turn(
            int index,
            String stageName,
            String questionType,
            int score,
            String feedback,
            DimensionEvaluation... dimensions) {
        return new InterviewReportAssembler.EvaluatedTurn(
                index,
                stageName,
                "请说明你的处理思路。",
                "我会先核对约束，再比较方案。",
                questionType,
                score,
                feedback,
                List.of(dimensions),
                List.of("FOUNDATIONS"),
                List.of("CACHE_CONSISTENCY"),
                List.of("CAPACITY_EVIDENCE_MISSING"));
    }

    private static InterviewReportAssembler.StageSummary stage(
            JobInterviewStage stage, int score, DimensionEvaluation... dimensions) {
        return new InterviewReportAssembler.StageSummary(
                stage,
                score,
                List.of(dimensions),
                List.of("FOUNDATIONS"),
                List.of("CACHE_CONSISTENCY"),
                List.of("CAPACITY_EVIDENCE_MISSING"));
    }

    private static DimensionEvaluation dimension(
            String code, int score, String evidence, String comment) {
        return new DimensionEvaluation(code, score, evidence, comment);
    }
}
