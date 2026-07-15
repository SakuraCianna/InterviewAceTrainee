package icu.sakuracianna.mianba.aiwork.worker;

/** Worker 解析并校验后的面试评价结果。 */
public record InterviewEvaluation(
        int score,
        String feedback,
        String roundName,
        String nextQuestion) {

    public InterviewEvaluation normalized(boolean finalTurn) {
        int safeScore = Math.min(100, Math.max(0, score));
        String safeFeedback = clean(feedback, 1200, "回答已记录，请继续保持结构化表达。");
        String safeRound = clean(roundName, 80, "追问");
        String safeQuestion = finalTurn ? null : clean(nextQuestion, 2000, "请进一步说明你的判断依据和具体行动。");
        return new InterviewEvaluation(safeScore, safeFeedback, safeRound, safeQuestion);
    }

    /**
     * 按可信面试策略归一化模型输出。
     *
     * IELTS 的可见文本必须为英文；下一轮名称由服务端阶段表决定；明显重复的问题会替换为
     * 对应阶段的兜底题，防止模型漂移破坏面试流程。
     */
    public InterviewEvaluation normalized(InterviewAiGenerator.InterviewAiInput input) {
        InterviewPromptPolicy.Profile profile = InterviewPromptPolicy.profile(input.interviewType());
        int safeScore = Math.min(100, Math.max(0, score));
        String safeFeedback = clean(feedback, 1200, profile.feedbackFallback());
        String safeRound = InterviewPromptPolicy.nextStage(input);
        String safeQuestion = input.finalTurn()
                ? null
                : clean(nextQuestion, 2000, InterviewPromptPolicy.fallbackQuestion(input));

        if (profile.englishOnly()) {
            if (!InterviewPromptPolicy.isEnglishText(safeFeedback)) {
                safeFeedback = profile.feedbackFallback();
            }
            if (!InterviewPromptPolicy.isEnglishText(safeRound)) {
                safeRound = "IELTS Speaking";
            }
            if (safeQuestion != null && !InterviewPromptPolicy.isEnglishText(safeQuestion)) {
                safeQuestion = InterviewPromptPolicy.fallbackQuestion(input);
            }
        }
        if (safeQuestion != null
                && InterviewPromptPolicy.repeatsKnownQuestion(safeQuestion, input)) {
            safeQuestion = InterviewPromptPolicy.fallbackQuestion(input);
        }
        return new InterviewEvaluation(safeScore, safeFeedback, safeRound, safeQuestion);
    }

    private static String clean(String value, int limit, String fallback) {
        String normalized = value == null ? "" : removeUnsupportedCharacters(value).strip();
        if (normalized.isEmpty()) {
            return fallback;
        }
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= limit) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, limit));
    }

    private static String removeUnsupportedCharacters(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                    safe.append(current).append(value.charAt(++index));
                } else {
                    safe.append(' ');
                }
            } else if (Character.isLowSurrogate(current)) {
                safe.append(' ');
            } else if (Character.isISOControl(current)
                    && current != '\n' && current != '\r' && current != '\t') {
                // PostgreSQL text 不接受 NUL；其他不可见控制字符也统一替换，避免模型输出污染存储与日志。
                safe.append(' ');
            } else {
                safe.append(current);
            }
        }
        return safe.toString();
    }
}
