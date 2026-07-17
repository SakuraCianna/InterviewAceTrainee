package icu.sakuracianna.mianba.interview.packageflow;

import java.util.Optional;
import java.util.UUID;

public interface JobInterviewPackageService {
    JobInterviewPackageView create(
            UUID userId,
            UUID packageId,
            UUID firstSessionId,
            UUID materialId,
            String idempotencyKey);

    Optional<JobInterviewPackageView> active(UUID userId);

    JobInterviewPackageView get(UUID userId, UUID packageId);

    JobInterviewPackageView startStage(
            UUID userId,
            UUID packageId,
            JobInterviewStage stage,
            UUID sessionId,
            String idempotencyKey);
}
