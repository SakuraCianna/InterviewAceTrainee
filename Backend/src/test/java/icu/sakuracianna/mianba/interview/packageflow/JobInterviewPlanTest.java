package icu.sakuracianna.mianba.interview.packageflow;

import static icu.sakuracianna.mianba.interview.packageflow.JobInterviewStage.HR_FINAL;
import static icu.sakuracianna.mianba.interview.packageflow.JobInterviewStage.TECHNICAL_FIRST;
import static icu.sakuracianna.mianba.interview.packageflow.JobInterviewStage.TECHNICAL_SECOND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobInterviewPlanTest {
    @Test
    void chineseEnterpriseV1HasThreeOrderedDynamicStages() {
        JobInterviewPlan plan = JobInterviewPlan.chineseEnterpriseV1();

        assertThat(JobInterviewStage.values())
                .containsExactly(TECHNICAL_FIRST, TECHNICAL_SECOND, HR_FINAL);
        assertThat(plan.stages())
                .extracting(JobInterviewPlan.StagePlan::code)
                .containsExactly(TECHNICAL_FIRST, TECHNICAL_SECOND, HR_FINAL);
        assertThat(plan.stages())
                .extracting(stage -> stage.code().sequence())
                .containsExactly(1, 2, 3);
    }

    @Test
    void chineseEnterpriseV1ContainsCompleteStageSnapshots() {
        JobInterviewPlan plan = JobInterviewPlan.chineseEnterpriseV1();

        assertThat(plan.stage(TECHNICAL_FIRST))
                .isEqualTo(new JobInterviewPlan.StagePlan(
                        TECHNICAL_FIRST,
                        8,
                        12,
                        50,
                        List.of(
                                "INTRODUCTION",
                                "RESUME_VERIFICATION",
                                "FOUNDATIONS",
                                "ROLE_KNOWLEDGE",
                                "ALGORITHM_REASONING")));
        assertThat(plan.stage(TECHNICAL_SECOND))
                .isEqualTo(new JobInterviewPlan.StagePlan(
                        TECHNICAL_SECOND,
                        7,
                        12,
                        60,
                        List.of(
                                "PROJECT_DEEP_DIVE",
                                "SYSTEM_DESIGN",
                                "TRADEOFF",
                                "INCIDENT_RESPONSE",
                                "BUSINESS_IMPACT")));
        assertThat(plan.stage(HR_FINAL))
                .isEqualTo(new JobInterviewPlan.StagePlan(
                        HR_FINAL,
                        5,
                        8,
                        25,
                        List.of(
                                "MOTIVATION",
                                "STABILITY",
                                "COLLABORATION_VALUES",
                                "CAREER_PLANNING",
                                "COMPENSATION_EXPECTATION",
                                "TECHNICAL_RISK_VERIFICATION")));
    }

    @Test
    void copiesInputsAndDoesNotExposeMutableCollections() {
        List<String> sections = new ArrayList<>(List.of("INTRODUCTION"));
        JobInterviewPlan.StagePlan stage = new JobInterviewPlan.StagePlan(
                TECHNICAL_FIRST, 1, 2, 10, sections);
        List<JobInterviewPlan.StagePlan> stages = new ArrayList<>(List.of(stage));
        JobInterviewPlan plan = new JobInterviewPlan(stages);

        sections.add("FOUNDATIONS");
        stages.clear();

        assertThat(stage.requiredSections()).containsExactly("INTRODUCTION");
        assertThat(plan.stages()).containsExactly(stage);
        assertThatThrownBy(() -> stage.requiredSections().add("FOUNDATIONS"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.stages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateStageCodes() {
        JobInterviewPlan.StagePlan first = stage(TECHNICAL_FIRST, List.of("INTRODUCTION"));
        JobInterviewPlan.StagePlan duplicate = stage(TECHNICAL_FIRST, List.of("FOUNDATIONS"));

        assertThatThrownBy(() -> new JobInterviewPlan(List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate stage code");
    }

    @Test
    void rejectsInvalidTurnAndTargetRanges() {
        assertThatThrownBy(() -> new JobInterviewPlan.StagePlan(
                TECHNICAL_FIRST, 0, 2, 10, List.of("INTRODUCTION")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobInterviewPlan.StagePlan(
                TECHNICAL_FIRST, 3, 2, 10, List.of("INTRODUCTION")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobInterviewPlan.StagePlan(
                TECHNICAL_FIRST, 1, 2, 0, List.of("INTRODUCTION")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullBlankAndDuplicateSections() {
        assertThatThrownBy(() -> new JobInterviewPlan.StagePlan(
                TECHNICAL_FIRST, 1, 2, 10, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobInterviewPlan.StagePlan(
                TECHNICAL_FIRST, 1, 2, 10, Arrays.asList("INTRODUCTION", null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> stage(TECHNICAL_FIRST, List.of("INTRODUCTION", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stage(
                TECHNICAL_FIRST, List.of("INTRODUCTION", "INTRODUCTION")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate required section");
    }

    @Test
    void rejectsNullStagesAndStageCodes() {
        assertThatThrownBy(() -> new JobInterviewPlan(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobInterviewPlan(Arrays.asList(
                stage(TECHNICAL_FIRST, List.of("INTRODUCTION")), null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobInterviewPlan.StagePlan(
                null, 1, 2, 10, List.of("INTRODUCTION")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findsExactStageAndFailsClearlyWhenItIsUnknown() {
        JobInterviewPlan.StagePlan technical = stage(
                TECHNICAL_FIRST, List.of("INTRODUCTION"));
        JobInterviewPlan plan = new JobInterviewPlan(List.of(technical));

        assertThat(plan.stage(TECHNICAL_FIRST)).isSameAs(technical);
        assertThatThrownBy(() -> plan.stage(HR_FINAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown stage code");
        assertThatThrownBy(() -> plan.stage(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static JobInterviewPlan.StagePlan stage(
            JobInterviewStage code, List<String> requiredSections) {
        return new JobInterviewPlan.StagePlan(code, 1, 2, 10, requiredSections);
    }
}
