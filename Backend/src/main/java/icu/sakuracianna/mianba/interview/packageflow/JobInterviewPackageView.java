package icu.sakuracianna.mianba.interview.packageflow;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 仅供套餐所有者读取的安全投影，不包含材料、计划或上下文快照原文。 */
public record JobInterviewPackageView(
        UUID packageId,
        String status,
        JobInterviewStage currentStageCode,
        int chargedCredit,
        boolean adminUnlimitedUsage,
        Instant expiresAt,
        List<Stage> stages) {
    public JobInterviewPackageView {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currentStageCode, "currentStageCode");
        Objects.requireNonNull(expiresAt, "expiresAt");
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
    }

    public record Stage(
            JobInterviewStage stageCode,
            int sequence,
            String status,
            UUID sessionId,
            int minTurns,
            int maxTurns,
            int targetDurationMinutes,
            List<String> requiredSections) {
        public Stage {
            Objects.requireNonNull(stageCode, "stageCode");
            Objects.requireNonNull(status, "status");
            requiredSections = List.copyOf(Objects.requireNonNull(requiredSections, "requiredSections"));
        }
    }
}
