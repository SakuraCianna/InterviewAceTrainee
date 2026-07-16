package icu.sakuracianna.mianba.aiwork.worker;

import icu.sakuracianna.mianba.interview.packageflow.JobInterviewStage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 从已验证的结构化评价生成可立即展示的确定性基础报告。 */
final class InterviewReportAssembler {
    private static final int MAX_DIMENSIONS = 8;
    private static final int MAX_CODES = 16;
    private static final int MAX_EVIDENCE_PER_DIMENSION = 5;
    private static final int MAX_EVIDENCE_CODE_POINTS = 800;
    private static final int MAX_COMMENT_CODE_POINTS = 800;
    private static final int MAX_FEEDBACK_CODE_POINTS = 1_200;
    private static final Pattern DIMENSION_CODE =
            Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");
    private static final Pattern CHINESE_DECISIVE_OUTCOME = Pattern.compile(
            "(?:建议|推荐|应该|应当|可以|可予|同意|决定|确认|直接|予以|值得)"
                    + ".{0,6}(?:录用|聘用)"
                    + "|(?:已|已经|将|会|确定|确认|决定).{0,4}(?:录用|聘用)"
                    + "|(?:录用|聘用)(?:该|此|这名)?(?:候选人|应聘者)"
                    + "|(?:面试|考试|考核|评估)(?:已|已经|顺利|成功)?通过"
                    + "|(?:已|已经|顺利|成功)?通过(?:了)?(?:本轮|本次|最终)?"
                    + "(?:面试|考试|考核|评估)"
                    + "|(?:发放|给予|给出|发送|获得|收到|承诺|保证).{0,6}\\boffer\\b"
                    + "|(?:承诺|保证|确定).{0,8}(?:薪酬|薪资|工资|年薪|月薪)"
                    + "|(?:薪酬|薪资|工资|年薪|月薪).{0,4}"
                    + "(?:承诺|保证|确定为|将为|不低于|至少)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ENGLISH_DECISIVE_OUTCOME = Pattern.compile(
            "\\b(?:recommend|suggest|approve|decide|propose)(?:ed|s|ing)?\\s+"
                    + "(?:(?:that\\s+(?:we|the\\s+company)\\s+)|to\\s+)?"
                    + "(?:hire|hired|hiring|employ|employed|employing)\\b"
                    + "|\\b(?:hire|employ)\\s+(?:the\\s+)?"
                    + "(?:candidate|applicant|interviewee)\\b"
                    + "|\\b(?:we|the\\s+company|the\\s+employer)\\s+"
                    + "(?:should|will|must|can)\\s+(?:hire|employ)\\b"
                    + "|\\b(?:candidate|applicant|interviewee|they|he|she)\\s+"
                    + "(?:(?:is|are|was|were|has\\s+been|should\\s+be|will\\s+be)\\s+)"
                    + "(?:hired|employed)\\b"
                    + "|\\b(?:recommended|approved)\\s+for\\s+hire\\b"
                    + "|\\b(?:extend|issue|make|give|send|guarantee|promise|accept|receive)"
                    + "(?:s|ed|ing)?\\s+(?:the\\s+candidate\\s+)?(?:an?\\s+)?"
                    + "(?:(?:job|employment)\\s+)?offer\\b"
                    + "|\\b(?:job|employment)\\s+offer\\b"
                    + "|\\boffer\\s+(?:the\\s+)?(?:candidate|applicant)\\s+employment\\b"
                    + "|\\bpass(?:ed|es|ing)?\\s+(?:the\\s+)?"
                    + "(?:interview|exam|examination|assessment)\\b"
                    + "|\\b(?:interview|exam|examination|assessment)\\s+"
                    + "(?:(?:is|was|has\\s+been)\\s+)?passed\\b"
                    + "|\\b(?:salary|compensation|pay)\\s+(?:commitment|guarantee)\\b"
                    + "|\\b(?:salary|compensation|pay)\\s+"
                    + "(?:is|was|will\\s+be|would\\s+be|has\\s+been)\\s+"
                    + "(?:guaranteed|promised|committed)\\b"
                    + "|\\b(?:guarantee|promise|commit(?:\\s+to)?)\\s+"
                    + "(?:a\\s+)?(?:specific\\s+)?"
                    + "(?:salary|compensation|pay)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    ReportDraft assembleSession(SessionReportInput input) {
        Objects.requireNonNull(input, "input");
        if (input.turns().isEmpty()) {
            throw new IllegalArgumentException("At least one evaluated turn is required");
        }
        List<EvaluatedTurn> turns = input.turns().stream()
                .sorted(Comparator.comparingInt(EvaluatedTurn::turnIndex))
                .toList();
        requireDistinct(turns, EvaluatedTurn::turnIndex, "turn index");

        CopyPolicy copy = CopyPolicy.forInterviewType(input.interviewType());
        int totalScore = roundedAverage(turns, EvaluatedTurn::overallScore);
        List<DimensionView> dimensions = aggregateDimensions(turns.stream()
                .flatMap(turn -> turn.dimensions().stream()
                        .map(dimension -> new DimensionSource(
                                turn.turnIndex(), safeDimension(dimension, copy))))
                .toList());
        List<String> strengths = dimensionSummaries(dimensions, copy, true);
        List<String> improvements = dimensionSummaries(dimensions, copy, false);

        List<Map<String, Object>> displayedTurns = new ArrayList<>();
        for (EvaluatedTurn turn : turns) {
            Map<String, Object> displayed = new LinkedHashMap<>();
            displayed.put("turn_index", turn.turnIndex());
            displayed.put("stage_name", copy.stageName(turn));
            displayed.put("question", turn.question());
            displayed.put("answer", turn.answer());
            displayed.put("question_type", turn.questionType());
            displayed.put("score", turn.overallScore());
            displayed.put("feedback", safeEvaluationText(turn.overallFeedback(), copy));
            displayedTurns.add(displayed);
        }

        Map<String, Object> report = commonReport(
                "SESSION",
                totalScore,
                copy,
                input.enhancementQueued(),
                input.promptVersion(),
                input.rubricVersion(),
                input.outputSchemaVersion(),
                dimensions,
                strengths,
                improvements,
                sortedCodes(turns, EvaluatedTurn::coveredSections),
                sortedCodes(turns, EvaluatedTurn::coveredTopics),
                sortedCodes(turns, EvaluatedTurn::riskFlags));
        report.put("session_id", input.sessionId().toString());
        report.put("interview_type", input.interviewType());
        report.put("stage_code", input.stageCode());
        report.put("summary", copy.sessionSummary(
                turns.size(), totalScore, weakestComment(dimensions)));
        report.put("material_context_applied", input.materialContextApplied());
        if (turns.stream().anyMatch(turn -> "ALGORITHM_REASONING".equals(turn.questionType()))) {
            report.put("algorithm_assessment_mode", "SPOKEN_REASONING_ONLY");
            report.put("assessment_notice", copy.algorithmNotice());
        }
        report.put("turns", displayedTurns);
        return new ReportDraft(totalScore, report);
    }

    ReportDraft assemblePackage(PackageReportInput input) {
        Objects.requireNonNull(input, "input");
        if (input.stages().size() != JobInterviewStage.values().length) {
            throw new IllegalArgumentException("Package report requires exactly three stages");
        }
        requireDistinct(input.stages(), StageSummary::stage, "package stage");
        Set<JobInterviewStage> actualStages = new HashSet<>();
        input.stages().forEach(stage -> actualStages.add(stage.stage()));
        if (!actualStages.equals(Set.of(JobInterviewStage.values()))) {
            throw new IllegalArgumentException("Package report requires the fixed three stages");
        }
        List<StageSummary> stages = input.stages().stream()
                .sorted(Comparator.comparingInt(stage -> stage.stage().sequence()))
                .toList();
        CopyPolicy copy = CopyPolicy.chinese();
        int totalScore = roundedAverage(stages, StageSummary::totalScore);
        List<DimensionView> dimensions = aggregateDimensions(stages.stream()
                .flatMap(stage -> stage.dimensions().stream()
                        .map(dimension -> new DimensionSource(
                                stage.stage().sequence(), safeDimension(dimension, copy))))
                .toList());
        List<String> strengths = dimensionSummaries(dimensions, copy, true);
        List<String> improvements = dimensionSummaries(dimensions, copy, false);

        List<Map<String, Object>> stageViews = new ArrayList<>();
        for (StageSummary stage : stages) {
            Map<String, Object> stageView = new LinkedHashMap<>();
            stageView.put("stage_code", stage.stage().name());
            stageView.put("sequence_no", stage.stage().sequence());
            stageView.put("total_score", stage.totalScore());
            stageViews.add(stageView);
        }

        Map<String, Object> report = commonReport(
                "PACKAGE",
                totalScore,
                copy,
                input.enhancementQueued(),
                input.promptVersion(),
                input.rubricVersion(),
                input.outputSchemaVersion(),
                dimensions,
                strengths,
                improvements,
                sortedCodes(stages, StageSummary::coveredSections),
                sortedCodes(stages, StageSummary::coveredTopics),
                sortedCodes(stages, StageSummary::riskFlags));
        report.put("package_id", input.packageId().toString());
        report.put("interview_type", "job");
        report.put("summary", copy.packageSummary(totalScore, weakestComment(dimensions)));
        report.put("algorithm_assessment_mode", "SPOKEN_REASONING_ONLY");
        report.put("assessment_notice", copy.algorithmNotice());
        report.put("stages", stageViews);
        return new ReportDraft(totalScore, report);
    }

    private static Map<String, Object> commonReport(
            String scope,
            int totalScore,
            CopyPolicy copy,
            boolean enhancementQueued,
            String promptVersion,
            String rubricVersion,
            String outputSchemaVersion,
            List<DimensionView> dimensions,
            List<String> strengths,
            List<String> improvements,
            List<String> coveredSections,
            List<String> coveredTopics,
            List<String> riskFlags) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report_scope", scope);
        report.put("total_score", totalScore);
        report.put("readiness_level", copy.readinessLevel(totalScore));
        report.put("score_explanation", copy.scoreExplanation(scope));
        report.put("summary_source", "TEMPLATE");
        report.put("generation_status", enhancementQueued ? "ENHANCING" : "BASE_READY");
        report.put("current_revision", 1);
        report.put("prompt_version", promptVersion);
        report.put("rubric_version", rubricVersion);
        report.put("output_schema_version", outputSchemaVersion);
        report.put("dimensions", dimensionMaps(dimensions));
        report.put("strengths", strengths);
        report.put("improvements", improvements);
        report.put("priority_actions", priorityActions(dimensions, copy));
        report.put("risk_flags", riskFlags);
        report.put("covered_sections", coveredSections);
        report.put("covered_topics", coveredTopics);
        report.put("recommended_drills", copy.recommendedDrills(totalScore));
        report.put("next_plan", copy.nextPlan());
        return report;
    }

    private static List<Map<String, Object>> dimensionMaps(List<DimensionView> dimensions) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (DimensionView dimension : dimensions) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("code", dimension.code());
            value.put("score", dimension.score());
            value.put("comment", dimension.comment());
            value.put("evidence", dimension.evidence());
            values.add(value);
        }
        return values;
    }

    /**
     * 基础报告的可信输出边界。模型自由文本即使已经通过结构校验，也不能形成招聘、
     * 考试或薪酬决定；命中时使用同语言中性训练反馈，避免报告组装失败。
     */
    private static DimensionEvaluation safeDimension(
            DimensionEvaluation dimension, CopyPolicy copy) {
        return new DimensionEvaluation(
                dimension.code(),
                dimension.score(),
                safeEvaluationText(dimension.evidence(), copy),
                safeEvaluationText(dimension.comment(), copy));
    }

    private static String safeEvaluationText(String value, CopyPolicy copy) {
        if (CHINESE_DECISIVE_OUTCOME.matcher(value).find()
                || ENGLISH_DECISIVE_OUTCOME.matcher(value).find()) {
            return copy.neutralTrainingFeedback();
        }
        return value;
    }

    private static List<DimensionView> aggregateDimensions(List<DimensionSource> sources) {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("At least one dimension is required");
        }
        Map<String, MutableDimension> grouped = new java.util.TreeMap<>();
        sources.stream()
                .sorted(Comparator.comparingInt(DimensionSource::order)
                        .thenComparing(source -> source.dimension().code()))
                .forEach(source -> grouped
                        .computeIfAbsent(
                                source.dimension().code(), MutableDimension::new)
                        .add(source));
        return grouped.values().stream().map(MutableDimension::toView).toList();
    }

    private static List<String> dimensionSummaries(
            List<DimensionView> dimensions, CopyPolicy copy, boolean strongestFirst) {
        Comparator<DimensionView> comparator = Comparator.comparingInt(DimensionView::score);
        if (strongestFirst) {
            comparator = comparator.reversed();
        }
        return dimensions.stream()
                .sorted(comparator.thenComparing(DimensionView::code))
                .limit(Math.min(2, dimensions.size()))
                .map(copy::dimensionSummary)
                .toList();
    }

    private static List<String> priorityActions(
            List<DimensionView> dimensions, CopyPolicy copy) {
        return dimensions.stream()
                .sorted(Comparator.comparingInt(DimensionView::score)
                        .thenComparing(DimensionView::code))
                .limit(Math.min(2, dimensions.size()))
                .map(copy::priorityAction)
                .toList();
    }

    private static String weakestComment(List<DimensionView> dimensions) {
        return dimensions.stream()
                .min(Comparator.comparingInt(DimensionView::score)
                        .thenComparing(DimensionView::code))
                .orElseThrow()
                .comment();
    }

    private static <T> int roundedAverage(List<T> values, Function<T, Integer> score) {
        return (int) Math.round(values.stream().mapToInt(score::apply).average().orElseThrow());
    }

    private static <T> List<String> sortedCodes(
            List<T> values, Function<T, List<String>> extractor) {
        TreeSet<String> codes = new TreeSet<>();
        values.forEach(value -> codes.addAll(extractor.apply(value)));
        return List.copyOf(codes);
    }

    private static <T, K> void requireDistinct(
            List<T> values, Function<T, K> key, String label) {
        Set<K> seen = new HashSet<>();
        for (T value : values) {
            if (!seen.add(key.apply(value))) {
                throw new IllegalArgumentException("Duplicate " + label);
            }
        }
    }

    private static List<DimensionEvaluation> validateDimensions(
            List<DimensionEvaluation> dimensions) {
        List<DimensionEvaluation> copied = copyList("dimensions", dimensions);
        if (copied.isEmpty() || copied.size() > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("dimensions must contain between 1 and 8 items");
        }
        Set<String> seen = new HashSet<>();
        for (DimensionEvaluation dimension : copied) {
            validateDimension(dimension);
            if (!seen.add(dimension.code())) {
                throw new IllegalArgumentException("Duplicate dimension code");
            }
        }
        return copied;
    }

    private static void validateDimension(DimensionEvaluation dimension) {
        if (dimension == null) {
            throw new IllegalArgumentException("dimensions must not contain null");
        }
        requireCode("dimension code", dimension.code(), 48);
        requireScore("dimension score", dimension.score());
        requireText("dimension evidence", dimension.evidence(), MAX_EVIDENCE_CODE_POINTS);
        requireText("dimension comment", dimension.comment(), MAX_COMMENT_CODE_POINTS);
    }

    private static List<String> validateCodes(String name, List<String> values) {
        List<String> copied = copyList(name, values);
        if (copied.size() > MAX_CODES) {
            throw new IllegalArgumentException(name + " must contain at most 16 codes");
        }
        for (String value : copied) {
            requireCode(name, value, 64);
        }
        return copied;
    }

    private static void requireCode(String name, String value, int maxLength) {
        if (value == null || value.length() > maxLength
                || !DIMENSION_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void requireScore(String name, int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }

    private static String requireText(String name, String value, int maxCodePoints) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }

    private static String requireVersion(String name, String value, int maxLength) {
        String validated = requireText(name, value, maxLength);
        if (validated.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return validated;
    }

    private static <T> List<T> copyList(String name, Collection<T> values) {
        if (values == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            if (value == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return (Map<String, Object>) freeze(source);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key.toString(), freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            return Collections.unmodifiableList(collection.stream()
                    .map(InterviewReportAssembler::freeze)
                    .toList());
        }
        return value;
    }

    record SessionReportInput(
            UUID sessionId,
            String interviewType,
            String stageCode,
            boolean enhancementQueued,
            String promptVersion,
            String rubricVersion,
            String outputSchemaVersion,
            boolean materialContextApplied,
            List<EvaluatedTurn> turns) {
        SessionReportInput {
            Objects.requireNonNull(sessionId, "sessionId");
            interviewType = requireText("interviewType", interviewType, 32);
            stageCode = requireText("stageCode", stageCode, 64);
            promptVersion = requireVersion("promptVersion", promptVersion, 80);
            rubricVersion = requireVersion("rubricVersion", rubricVersion, 80);
            outputSchemaVersion = requireVersion(
                    "outputSchemaVersion", outputSchemaVersion, 40);
            turns = copyList("turns", turns);
        }
    }

    record EvaluatedTurn(
            int turnIndex,
            String stageName,
            String question,
            String answer,
            String questionType,
            int overallScore,
            String overallFeedback,
            List<DimensionEvaluation> dimensions,
            List<String> coveredSections,
            List<String> coveredTopics,
            List<String> riskFlags) {
        EvaluatedTurn {
            if (turnIndex < 0) {
                throw new IllegalArgumentException("turn index must not be negative");
            }
            stageName = requireText("stageName", stageName, 160);
            question = requireText("question", question, 8_000);
            answer = requireText("answer", answer, 20_000);
            requireCode("questionType", questionType, 48);
            requireScore("overall score", overallScore);
            overallFeedback = requireText(
                    "overallFeedback", overallFeedback, MAX_FEEDBACK_CODE_POINTS);
            dimensions = validateDimensions(dimensions);
            coveredSections = validateCodes("coveredSections", coveredSections);
            coveredTopics = validateCodes("coveredTopics", coveredTopics);
            riskFlags = validateCodes("riskFlags", riskFlags);
        }
    }

    record PackageReportInput(
            UUID packageId,
            boolean enhancementQueued,
            String promptVersion,
            String rubricVersion,
            String outputSchemaVersion,
            List<StageSummary> stages) {
        PackageReportInput {
            Objects.requireNonNull(packageId, "packageId");
            promptVersion = requireVersion("promptVersion", promptVersion, 80);
            rubricVersion = requireVersion("rubricVersion", rubricVersion, 80);
            outputSchemaVersion = requireVersion(
                    "outputSchemaVersion", outputSchemaVersion, 40);
            stages = copyList("stages", stages);
        }
    }

    record StageSummary(
            JobInterviewStage stage,
            int totalScore,
            List<DimensionEvaluation> dimensions,
            List<String> coveredSections,
            List<String> coveredTopics,
            List<String> riskFlags) {
        StageSummary {
            Objects.requireNonNull(stage, "stage");
            requireScore("stage total score", totalScore);
            dimensions = validateDimensions(dimensions);
            coveredSections = validateCodes("coveredSections", coveredSections);
            coveredTopics = validateCodes("coveredTopics", coveredTopics);
            riskFlags = validateCodes("riskFlags", riskFlags);
        }
    }

    record ReportDraft(int totalScore, Map<String, Object> body) {
        ReportDraft {
            requireScore("report total score", totalScore);
            if (body == null) {
                throw new IllegalArgumentException("report body must not be null");
            }
            body = immutableMap(body);
        }
    }

    private record DimensionSource(int order, DimensionEvaluation dimension) {
    }

    private record DimensionView(
            String code, int score, String comment, List<String> evidence) {
    }

    private static final class MutableDimension {
        private final String code;
        private int scoreTotal;
        private int count;
        private int weakestScore = Integer.MAX_VALUE;
        private int weakestOrder = Integer.MAX_VALUE;
        private String weakestComment;
        private final Set<String> evidence = new LinkedHashSet<>();

        private MutableDimension(String code) {
            this.code = code;
        }

        private void add(DimensionSource source) {
            DimensionEvaluation dimension = source.dimension();
            scoreTotal += dimension.score();
            count++;
            if (dimension.score() < weakestScore
                    || (dimension.score() == weakestScore && source.order() < weakestOrder)) {
                weakestScore = dimension.score();
                weakestOrder = source.order();
                weakestComment = dimension.comment();
            }
            if (evidence.size() < MAX_EVIDENCE_PER_DIMENSION) {
                evidence.add(dimension.evidence());
            }
        }

        private DimensionView toView() {
            return new DimensionView(
                    code,
                    (int) Math.round((double) scoreTotal / count),
                    weakestComment,
                    List.copyOf(evidence));
        }
    }

    private record CopyPolicy(boolean english, List<String> trustedStages) {
        private static CopyPolicy forInterviewType(String interviewType) {
            InterviewPromptPolicy.Profile profile = InterviewPromptPolicy.profile(interviewType);
            return new CopyPolicy(profile.englishOnly(), profile.stages());
        }

        private static CopyPolicy chinese() {
            return new CopyPolicy(false, List.of());
        }

        private String stageName(EvaluatedTurn turn) {
            if (!english) {
                return turn.stageName();
            }
            int index = Math.min(turn.turnIndex(), trustedStages.size() - 1);
            return trustedStages.get(index);
        }

        private String dimensionSummary(DimensionView dimension) {
            return english
                    ? dimension.code() + " (" + dimension.score() + "/100): "
                            + dimension.comment()
                    : dimension.code() + "（" + dimension.score() + " 分）："
                            + dimension.comment();
        }

        private String priorityAction(DimensionView dimension) {
            return english
                    ? "Prioritize " + dimension.code() + ": " + dimension.comment()
                    : "优先改进 " + dimension.code() + "：" + dimension.comment();
        }

        private String sessionSummary(int turnCount, int totalScore, String priorityFeedback) {
            return english
                    ? "Completed " + turnCount + " rounds with an overall average score of "
                            + totalScore + "/100. Top improvement priority: " + priorityFeedback
                    : "本场完成 " + turnCount + " 轮，综合平均 " + totalScore
                            + " 分。优先改进：" + priorityFeedback;
        }

        private String packageSummary(int totalScore, String priorityFeedback) {
            return "工作面试三场均已完成，综合平均 " + totalScore
                    + " 分。优先改进：" + priorityFeedback;
        }

        private String readinessLevel(int score) {
            if (score >= 85) {
                return english ? "Well prepared" : "准备充分";
            }
            if (score >= 70) {
                return english ? "Mostly ready" : "基本就绪";
            }
            return english ? "More practice needed" : "需要加强";
        }

        private String scoreExplanation(String scope) {
            if (english) {
                return "The total score is the rounded arithmetic mean of the turn scores "
                        + "and is provided for practice guidance only.";
            }
            return "PACKAGE".equals(scope)
                    ? "总分为三场面试总分的算术平均值（四舍五入），仅用于训练参考。"
                    : "总分为本场所有轮次评分的算术平均值（四舍五入），仅用于训练参考。";
        }

        private List<String> nextPlan() {
            return english
                    ? List.of(
                            "Rewrite the lowest-scoring response using the feedback above",
                            "Add one specific example and explain its relevance clearly",
                            "Complete a timed IELTS speaking attempt and compare it with this report")
                    : List.of(
                            "先按反馈重写最低分轮次的回答",
                            "补充一个可验证的具体事例或数据",
                            "完成一次同类型限时复述并与本报告对照");
        }

        private List<String> recommendedDrills(int score) {
            if (english) {
                return score < 70
                        ? List.of(
                                "IELTS answer structure practice",
                                "Two-minute timed speaking",
                                "Specific examples and broader language range")
                        : List.of(
                                "IELTS follow-up question practice",
                                "Concise speaking with clear signposting");
            }
            return score < 70
                    ? List.of("STAR 结构拆解", "一题两分钟限时表达", "证据与结果量化")
                    : List.of("追问压力测试", "答案压缩与重点前置");
        }

        private String algorithmNotice() {
            return english
                    ? "Algorithm questions assess spoken reasoning, complexity, and edge cases only; "
                            + "no code was executed, compiled, or judged online."
                    : "算法题仅评价口述思路、复杂度与边界，未执行或编译代码，也未进行在线判题。";
        }

        private String neutralTrainingFeedback() {
            return english
                    ? "This evaluation exceeds the practice-feedback boundary; "
                            + "focus on observable evidence and actionable improvement."
                    : "该段评价超出训练反馈边界，请仅依据可观察证据继续练习。";
        }
    }
}
