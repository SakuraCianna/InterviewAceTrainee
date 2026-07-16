package icu.sakuracianna.mianba.interview.packageflow;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record JobInterviewPlan(List<StagePlan> stages) {
    public JobInterviewPlan {
        Objects.requireNonNull(stages, "stages");
        stages = List.copyOf(stages);

        Set<JobInterviewStage> stageCodes = EnumSet.noneOf(JobInterviewStage.class);
        for (StagePlan stage : stages) {
            if (!stageCodes.add(stage.code())) {
                throw new IllegalArgumentException("Duplicate stage code: " + stage.code());
            }
        }
    }

    public static JobInterviewPlan chineseEnterpriseV1() {
        return new JobInterviewPlan(List.of(
                new StagePlan(
                        JobInterviewStage.TECHNICAL_FIRST,
                        8,
                        12,
                        50,
                        List.of(
                                "INTRODUCTION",
                                "RESUME_VERIFICATION",
                                "FOUNDATIONS",
                                "ROLE_KNOWLEDGE",
                                "ALGORITHM_REASONING")),
                new StagePlan(
                        JobInterviewStage.TECHNICAL_SECOND,
                        7,
                        12,
                        60,
                        List.of(
                                "PROJECT_DEEP_DIVE",
                                "SYSTEM_DESIGN",
                                "TRADEOFF",
                                "INCIDENT_RESPONSE",
                                "BUSINESS_IMPACT")),
                new StagePlan(
                        JobInterviewStage.HR_FINAL,
                        5,
                        8,
                        25,
                        List.of(
                                "MOTIVATION",
                                "STABILITY",
                                "COLLABORATION_VALUES",
                                "CAREER_PLANNING",
                                "COMPENSATION_EXPECTATION",
                                "TECHNICAL_RISK_VERIFICATION"))));
    }

    public StagePlan stage(JobInterviewStage code) {
        Objects.requireNonNull(code, "code");
        return stages.stream()
                .filter(stage -> stage.code() == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown stage code: " + code));
    }

    public record StagePlan(
            JobInterviewStage code,
            int minTurns,
            int maxTurns,
            int targetMinutes,
            List<String> requiredSections) {
        public StagePlan {
            Objects.requireNonNull(code, "code");
            if (minTurns < 1) {
                throw new IllegalArgumentException("minTurns must be positive");
            }
            if (maxTurns < minTurns) {
                throw new IllegalArgumentException("maxTurns must not be less than minTurns");
            }
            if (targetMinutes < 1) {
                throw new IllegalArgumentException("targetMinutes must be positive");
            }

            Objects.requireNonNull(requiredSections, "requiredSections");
            requiredSections = List.copyOf(requiredSections);
            Set<String> sectionNames = new HashSet<>();
            for (String section : requiredSections) {
                if (section.isBlank()) {
                    throw new IllegalArgumentException("Required section must not be blank");
                }
                if (!sectionNames.add(section)) {
                    throw new IllegalArgumentException("Duplicate required section: " + section);
                }
            }
        }
    }
}
