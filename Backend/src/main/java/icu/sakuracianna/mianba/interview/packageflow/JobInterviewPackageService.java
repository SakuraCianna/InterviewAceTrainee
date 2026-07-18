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

    /** 使用请求级本地 RAG 首题创建套餐，不保存材料或材料派生摘要。 */
    JobInterviewPackageView createPersonalized(
            UUID userId,
            UUID packageId,
            UUID firstSessionId,
            String idempotencyKey,
            String openingQuestion);

    Optional<JobInterviewPackageView> active(UUID userId);

    JobInterviewPackageView get(UUID userId, UUID packageId);

    JobInterviewPackageView startStage(
            UUID userId,
            UUID packageId,
            JobInterviewStage stage,
            UUID sessionId,
            String idempotencyKey);
}
