package icu.sakuracianna.mianba.aiwork.worker;

import icu.sakuracianna.mianba.interview.packageflow.JobInterviewPlan.StagePlan;
import java.util.Set;

/** 根据不可变阶段计划与可信覆盖结果，确定阶段是否继续或完成。 */
public final class StageProgressPolicy {

    public enum Decision {
        CONTINUE,
        COMPLETE,
        FORCE_COMPLETE
    }

    public Decision decide(
            StagePlan plan,
            int completedTurns,
            Set<String> coveredSections,
            boolean modelSuggestsEnd) {
        requirePlanAndCoverage(plan, coveredSections);
        if (completedTurns < 0) {
            throw new IllegalArgumentException("completedTurns must not be negative");
        }

        if (completedTurns >= plan.maxTurns()) {
            return Decision.FORCE_COMPLETE;
        }
        if (completedTurns >= plan.minTurns()
                && coveredSections.containsAll(plan.requiredSections())
                && modelSuggestsEnd) {
            return Decision.COMPLETE;
        }
        return Decision.CONTINUE;
    }

    public void validateNextSection(
            StagePlan plan,
            Set<String> coveredSections,
            String nextSection,
            boolean linkedFollowUp) {
        requirePlanAndCoverage(plan, coveredSections);
        if (nextSection == null || nextSection.isBlank()) {
            throw new IllegalArgumentException("nextSection must not be blank");
        }
        if (!plan.requiredSections().contains(nextSection)) {
            throw new IllegalArgumentException("nextSection is not required by the current stage: " + nextSection);
        }
        if (coveredSections.contains(nextSection) && !linkedFollowUp) {
            throw new IllegalArgumentException("Covered section requires a linked follow-up: " + nextSection);
        }
    }

    private static void requirePlanAndCoverage(StagePlan plan, Set<String> coveredSections) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        if (coveredSections == null) {
            throw new IllegalArgumentException("coveredSections must not be null");
        }
    }
}
