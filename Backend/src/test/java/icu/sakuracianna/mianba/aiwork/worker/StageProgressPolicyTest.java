package icu.sakuracianna.mianba.aiwork.worker;

import static icu.sakuracianna.mianba.aiwork.worker.StageProgressPolicy.Decision.COMPLETE;
import static icu.sakuracianna.mianba.aiwork.worker.StageProgressPolicy.Decision.CONTINUE;
import static icu.sakuracianna.mianba.aiwork.worker.StageProgressPolicy.Decision.FORCE_COMPLETE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import icu.sakuracianna.mianba.interview.packageflow.JobInterviewPlan;
import icu.sakuracianna.mianba.interview.packageflow.JobInterviewPlan.StagePlan;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StageProgressPolicyTest {

    private final StageProgressPolicy policy = new StageProgressPolicy();
    private final StagePlan firstStage = JobInterviewPlan.chineseEnterpriseV1().stages().getFirst();

    @Test
    void continuesWhenModelSuggestsEndingBeforeMinimumTurns() {
        assertThat(policy.decide(firstStage, 7, Set.copyOf(firstStage.requiredSections()), true))
                .isEqualTo(CONTINUE);
    }

    @Test
    void completesAfterMinimumTurnsWhenCoverageIsCompleteAndModelSuggestsEnding() {
        assertThat(policy.decide(firstStage, 8, Set.copyOf(firstStage.requiredSections()), true))
                .isEqualTo(COMPLETE);
    }

    @Test
    void continuesWhenRequiredSectionCoverageIsIncomplete() {
        assertThat(policy.decide(firstStage, 8, Set.of("INTRODUCTION"), true))
                .isEqualTo(CONTINUE);
    }

    @Test
    void continuesWhenCoverageIsCompleteButModelDoesNotSuggestEnding() {
        assertThat(policy.decide(firstStage, 8, Set.copyOf(firstStage.requiredSections()), false))
                .isEqualTo(CONTINUE);
    }

    @Test
    void forcesCompletionAtMaximumTurnsRegardlessOfCoverageAndModelSuggestion() {
        assertThat(policy.decide(firstStage, 12, Set.of(), false)).isEqualTo(FORCE_COMPLETE);
        assertThat(policy.decide(firstStage, 12, Set.copyOf(firstStage.requiredSections()), true))
                .isEqualTo(FORCE_COMPLETE);
        assertThat(policy.decide(firstStage, 13, Set.copyOf(firstStage.requiredSections()), true))
                .isEqualTo(FORCE_COMPLETE);
    }

    @Test
    void unknownCoveredSectionDoesNotReplaceMissingRequiredCoverage() {
        Set<String> coverage = new HashSet<>(firstStage.requiredSections());
        coverage.remove("ALGORITHM_REASONING");
        coverage.add("COMPENSATION_EXPECTATION");

        assertThat(policy.decide(firstStage, 8, coverage, true)).isEqualTo(CONTINUE);
    }

    @Test
    void rejectsInvalidDecisionInputs() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.decide(null, 8, Set.of(), true));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.decide(firstStage, 8, null, true));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.decide(firstStage, -1, Set.of(), true));
    }

    @Test
    void acceptsUncoveredRequiredSectionAsNextSection() {
        policy.validateNextSection(firstStage, Set.of("INTRODUCTION"), "FOUNDATIONS", false);
    }

    @Test
    void acceptsCoveredRequiredSectionOnlyForLinkedFollowUp() {
        policy.validateNextSection(firstStage, Set.of("FOUNDATIONS"), "FOUNDATIONS", true);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validateNextSection(
                        firstStage, Set.of("FOUNDATIONS"), "FOUNDATIONS", false));
    }

    @Test
    void acceptsCoveredSectionAsLinkedFollowUpWhenAllRequiredSectionsAreCovered() {
        policy.validateNextSection(
                firstStage,
                Set.copyOf(firstStage.requiredSections()),
                "ALGORITHM_REASONING",
                true);
    }

    @Test
    void rejectsSectionFromAnotherStageAndUnknownOrBlankSection() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validateNextSection(
                        firstStage, Set.of(), "COMPENSATION_EXPECTATION", false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validateNextSection(
                        firstStage, Set.of(), "UNKNOWN_SECTION", false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validateNextSection(firstStage, Set.of(), " ", false));
    }

    @Test
    void rejectsNullNextSectionInputs() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validateNextSection(null, Set.of(), "FOUNDATIONS", false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validateNextSection(firstStage, null, "FOUNDATIONS", false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validateNextSection(firstStage, Set.of(), null, false));
    }
}
