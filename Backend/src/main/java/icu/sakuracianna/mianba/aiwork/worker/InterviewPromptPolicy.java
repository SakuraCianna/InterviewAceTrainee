package icu.sakuracianna.mianba.aiwork.worker;

import icu.sakuracianna.mianba.interview.domain.InterviewType;
import icu.sakuracianna.mianba.interview.packageflow.JobInterviewPlan;
import icu.sakuracianna.mianba.interview.packageflow.JobInterviewStage;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面试类型对应的可信提示词策略。
 *
 * 阶段名称、评分维度和兜底问题全部由服务端定义，不能从简历或回答中派生，防止不可信
 * 文本通过伪造“下一轮规则”改变面试流程。
 */
final class InterviewPromptPolicy {
    private static final JobInterviewPlan JOB_PLAN = JobInterviewPlan.chineseEnterpriseV1();
    private static final Map<JobInterviewStage, List<String>> JOB_FALLBACK_QUESTIONS = Map.of(
            JobInterviewStage.TECHNICAL_FIRST,
            List.of(
                    "请用两分钟介绍与你应聘岗位最相关的经历。",
                    "请从简历中选择一个项目，说明你的真实职责和可验证结果。",
                    "请解释目标岗位最常用的一项基础原理及其适用边界。",
                    "请口述一道数组算法题的解题思路、复杂度和边界条件，不需要编写代码。"),
            JobInterviewStage.TECHNICAL_SECOND,
            List.of(
                    "请选择一个核心项目，说明最困难的技术问题以及你如何定位根因。",
                    "如果业务流量增长十倍，你会如何演进这个系统并验证容量？",
                    "请说明你在性能、一致性和复杂度之间做过的一次真实取舍。",
                    "请复盘一次线上故障，说明止损、定位、修复和预防过程。",
                    "这项技术方案最终带来了什么可验证的业务影响？"),
            JobInterviewStage.HR_FINAL,
            List.of(
                    "你为什么考虑这个岗位和这家公司？",
                    "什么因素会影响你在下一份工作的稳定投入？",
                    "请讲述一次与不同意见同事协作并达成结果的经历。",
                    "你未来三年的职业规划是什么？",
                    "请说明你的薪资预期、依据和可协商范围。",
                    "针对技术面暴露的风险点，你准备如何补足？"));
    private static final Map<InterviewType, Profile> PROFILES = Map.of(
            InterviewType.JOB,
            new Profile(
                    false,
                    "资深岗位面试官",
                    "岗位相关性、事实证据、问题解决、协作沟通和结果复盘",
                    List.of("岗位开场", "经历深挖", "岗位能力", "行为协作", "情景权衡", "动机匹配"),
                    List.of(
                            "请概述一段与你目标岗位最相关的经历。",
                            "请选取刚才经历中最具挑战性的一个决策，说明你的职责、行动和可验证结果。",
                            "针对目标岗位的一项核心能力，请说明你如何解决过一个具体难题以及取舍依据。",
                            "请讲述一次与持不同意见的同事协作的经历，你采取了什么行动，结果如何？",
                            "如果项目期限突然缩短且关键资源不足，你会如何排序目标、沟通风险并推进交付？",
                            "你为什么选择这个岗位？请结合自身能力差距说明入职后三个月的行动计划。"),
                    "回答已记录。建议使用具体事实说明你的职责、行动、取舍和可验证结果。"),
            InterviewType.POSTGRADUATE,
            new Profile(
                    false,
                    "研究生复试面试官",
                    "专业基础、研究动机、研究设计、批判思考和学业规划",
                    List.of("复试开场", "专业基础", "研究设计", "批判思考", "学业规划"),
                    List.of(
                            "请介绍你的专业基础，以及选择该研究方向的原因。",
                            "请选择本专业一个核心概念，说明其适用条件、局限及一个实际例子。",
                            "如果围绕目标研究方向设计一项小型研究，你会如何提出问题、选择方法并验证结论？",
                            "面对与你预期不一致的研究结果，你会怎样排查假设、数据和方法上的问题？",
                            "请说明入学后第一年的学习与研究计划，以及你准备如何弥补当前短板。"),
                    "回答已记录。建议补充准确的专业概念、研究依据和可执行的验证方法。"),
            InterviewType.CIVIL_SERVICE,
            new Profile(
                    false,
                    "公务员结构化面试考官",
                    "政治素养、综合分析、组织协调、应急处置、群众沟通和岗位匹配",
                    List.of("综合分析", "组织协调", "应急处置", "人际沟通", "岗位匹配"),
                    List.of(
                            "请结合实际，分析公共服务中效率与公平应如何平衡。",
                            "单位要在一周内开展一次面向群众的政策宣讲活动，你会如何组织并评估效果？",
                            "服务大厅突发系统故障并出现群众聚集和质疑，你会如何处置？",
                            "同事因分工意见与你发生冲突并影响进度，你会如何沟通和推进工作？",
                            "请结合报考岗位，说明你能为群众解决什么实际问题，以及仍需提升的能力。"),
                    "回答已记录。建议进一步明确群众立场、事实依据、执行步骤和风险预案。"),
            InterviewType.IELTS,
            new Profile(
                    true,
                    "a rigorous IELTS Speaking examiner and feedback coach",
                    "task relevance, fluency and coherence, lexical resource, and grammatical range and accuracy; do not infer pronunciation from a text transcript",
                    List.of(
                            "Part 1 · Introduction",
                            "Part 1 · Familiar Topics",
                            "Part 2 · Long Turn",
                            "Part 2 · Follow-up",
                            "Part 3 · Discussion",
                            "Part 3 · Critical Discussion"),
                    List.of(
                            "Please introduce yourself and describe a skill you want to improve.",
                            "What part of your daily routine would you most like to change, and why?",
                            "Describe a difficult decision you made. You should say what the decision was, what influenced you, what happened, and explain how you felt about it.",
                            "Looking back, would you make the same decision again? Why or why not?",
                            "Why do some people find major decisions more difficult than others?",
                            "Should schools do more to teach young people how to make responsible decisions? Why or why not?"),
                    "Your answer was recorded. Support your main idea with a specific example, use clear linking, and vary your vocabulary and sentence structures."));

    private InterviewPromptPolicy() {
    }

    /** 根据数据库中的稳定类型值取得提示词策略。 */
    static Profile profile(String rawType) {
        try {
            InterviewType type = InterviewType.valueOf(rawType.strip().toUpperCase(Locale.ROOT));
            return PROFILES.get(type);
        } catch (RuntimeException exception) {
            throw new AiWorkerException("AI_INPUT_INVALID", "面试类型不受支持", false, exception);
        }
    }

    /** 返回当前轮次由服务端规定的阶段名称。 */
    static String currentStage(InterviewAiGenerator.InterviewAiInput input) {
        return stageAt(profile(input.interviewType()), input.turnIndex());
    }

    /** 返回下一轮由服务端规定的阶段名称；最后一轮返回当前阶段。 */
    static String nextStage(InterviewAiGenerator.InterviewAiInput input) {
        int index = input.finalTurn() ? input.turnIndex() : input.turnIndex() + 1;
        return stageAt(profile(input.interviewType()), index);
    }

    /**
     * 为非法、空白或重复模型问题选择服务端兜底题。
     *
     * 每个轮次的题型不同，因此即使模型重复当前问题，恢复后仍能继续既定面试阶段。
     */
    static String fallbackQuestion(InterviewAiGenerator.InterviewAiInput input) {
        if (typeOf(input.interviewType()) == InterviewType.JOB) {
            List<String> questions = JOB_FALLBACK_QUESTIONS.get(jobStage(input));
            for (String question : questions) {
                if (!repeatsKnownQuestion(question, input)) {
                    return question;
                }
            }
            StagePolicy stage = stagePolicy(input);
            List<String> sections = stage.sections().stream().sorted().toList();
            String section = sections.get(Math.floorMod(input.turnIndex() + 1, sections.size()));
            return linkedFallbackQuestion(stage, section, input.turnIndex() + 1);
        }
        Profile profile = profile(input.interviewType());
        int index = Math.min(input.turnIndex() + 1, profile.fallbackQuestions().size() - 1);
        return profile.fallbackQuestions().get(index);
    }

    /** 检查候选问题是否与当前或既往问题发生明显的字面重复。 */
    static boolean repeatsKnownQuestion(
            String candidate,
            InterviewAiGenerator.InterviewAiInput input) {
        if (isLikelyDuplicate(candidate, input.question())) {
            return true;
        }
        return input.previousQuestions().stream()
                .anyMatch(previous -> isLikelyDuplicate(candidate, previous));
    }

    /**
     * 采用规范化文本和高词项覆盖率识别重复，避免仅改变标点或少量语气词绕过。
     * 该规则只用于选择安全兜底题，不会据此拒绝用户回答。
     */
    static boolean isLikelyDuplicate(String first, String second) {
        String left = comparable(first);
        String right = comparable(second);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        int shorterLength = Math.min(left.length(), right.length());
        return shorterLength >= 16 && (left.contains(right) || right.contains(left));
    }

    /** IELTS 输出只能包含拉丁字母及中性数字、空白和标点。 */
    static boolean isEnglishText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        boolean hasLatinLetter = false;
        for (int codePoint : value.codePoints().toArray()) {
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script != Character.UnicodeScript.LATIN) {
                return false;
            }
            hasLatinLetter = true;
        }
        return hasLatinLetter;
    }

    /** 返回只由服务端面试类型和套餐阶段码决定的代码目录。 */
    static StagePolicy stagePolicy(InterviewAiGenerator.InterviewAiInput input) {
        InterviewType type = typeOf(input.interviewType());
        if (type == InterviewType.JOB) {
            JobInterviewStage stage = jobStage(input);
            Set<String> sections = Set.copyOf(JOB_PLAN.stage(stage).requiredSections());
            return new StagePolicy(
                    stage.name(), jobStageLabel(stage), sections, sections, sections);
        }
        return switch (type) {
            case POSTGRADUATE -> new StagePolicy(
                    "POSTGRADUATE", "研究生复试",
                    Set.of("INTRODUCTION", "FOUNDATIONS", "RESEARCH_DESIGN",
                            "CRITICAL_THINKING", "ACADEMIC_PLANNING"),
                    Set.of("DOMAIN_FOUNDATIONS", "RESEARCH_POTENTIAL",
                            "CRITICAL_THINKING", "COMMUNICATION"),
                    Set.of("INTRODUCTION", "FOUNDATIONS", "RESEARCH_DESIGN",
                            "CRITICAL_THINKING", "ACADEMIC_PLANNING"));
            case CIVIL_SERVICE -> new StagePolicy(
                    "CIVIL_SERVICE", "公务员结构化面试",
                    Set.of("COMPREHENSIVE_ANALYSIS", "ORGANIZATION_COORDINATION",
                            "INCIDENT_RESPONSE", "PUBLIC_COMMUNICATION", "ROLE_FIT"),
                    Set.of("POLITICAL_LITERACY", "ANALYSIS", "EXECUTION",
                            "COMMUNICATION", "ROLE_FIT"),
                    Set.of("COMPREHENSIVE_ANALYSIS", "ORGANIZATION_COORDINATION",
                            "INCIDENT_RESPONSE", "PUBLIC_COMMUNICATION", "ROLE_FIT"));
            case IELTS -> new StagePolicy(
                    "IELTS", "IELTS Speaking",
                    Set.of("PART_1", "PART_2", "PART_3"),
                    Set.of("TASK_RELEVANCE", "FLUENCY_COHERENCE", "LEXICAL_RESOURCE",
                            "GRAMMATICAL_RANGE_ACCURACY"),
                    Set.of("INTRODUCTION", "FAMILIAR_TOPIC", "LONG_TURN",
                            "FOLLOW_UP", "DISCUSSION", "CRITICAL_DISCUSSION"));
            case JOB -> throw new IllegalStateException("JOB handled above");
        };
    }

    private static JobInterviewStage jobStage(InterviewAiGenerator.InterviewAiInput input) {
        String explicit = input.jobStageCode();
        if (explicit != null && !explicit.isBlank()) {
            try {
                return JobInterviewStage.valueOf(explicit.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new AiWorkerException(
                        "AI_INPUT_INVALID", "工作面试阶段不受支持", false, exception);
            }
        }
        String trustedRound = input.roundName() == null ? "" : input.roundName();
        String normalizedRound = trustedRound.strip().toUpperCase(Locale.ROOT);
        for (JobInterviewPlan.StagePlan stage : JOB_PLAN.stages()) {
            if (stage.requiredSections().contains(normalizedRound)) {
                return stage.code();
            }
        }
        if (trustedRound.contains("二面")) {
            return JobInterviewStage.TECHNICAL_SECOND;
        }
        if (trustedRound.toUpperCase(Locale.ROOT).contains("HR")) {
            return JobInterviewStage.HR_FINAL;
        }
        return JobInterviewStage.TECHNICAL_FIRST;
    }

    private static String linkedFallbackQuestion(
            StagePolicy stage,
            String section,
            int ordinal) {
        if ("HR_FINAL".equals(stage.code())) {
            return "请围绕%s（%s）完成第%d个关联追问：补充一个此前未提及的真实例子、个人判断和结果。"
                    .formatted(stage.label(), section, ordinal);
        }
        return "请围绕%s（%s）完成第%d个关联追问：补充一个此前未提及的具体证据、关键取舍和验证方式。"
                .formatted(stage.label(), section, ordinal);
    }

    private static InterviewType typeOf(String rawType) {
        try {
            return InterviewType.valueOf(rawType.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new AiWorkerException("AI_INPUT_INVALID", "面试类型不受支持", false, exception);
        }
    }

    private static String jobStageLabel(JobInterviewStage stage) {
        return switch (stage) {
            case TECHNICAL_FIRST -> "技术一面";
            case TECHNICAL_SECOND -> "技术二面";
            case HR_FINAL -> "HR 面";
        };
    }

    private static String stageAt(Profile profile, int requestedIndex) {
        int index = Math.min(Math.max(requestedIndex, 0), profile.stages().size() - 1);
        return profile.stages().get(index);
    }

    private static String comparable(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** 单一面试类型的不可变提示词配置。 */
    record Profile(
            boolean englishOnly,
            String role,
            String dimensions,
            List<String> stages,
            List<String> fallbackQuestions,
            String feedbackFallback) {
        Profile {
            stages = List.copyOf(stages);
            fallbackQuestions = List.copyOf(fallbackQuestions);
        }
    }

    record StagePolicy(
            String code,
            String label,
            Set<String> sections,
            Set<String> dimensions,
            Set<String> questionTypes) {
        StagePolicy {
            sections = Set.copyOf(sections);
            dimensions = Set.copyOf(dimensions);
            questionTypes = Set.copyOf(questionTypes);
        }
    }
}
