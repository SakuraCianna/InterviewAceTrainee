package icu.sakuracianna.mianba.interview.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** 面试历史列表的轻量投影，不加载完整报告 JSON。 */
public record InterviewHistoryView(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("interview_type") String interviewType,
        String status,
        @JsonProperty("current_step_index") int currentStepIndex,
        @JsonProperty("total_steps") int totalSteps,
        @JsonProperty("report_total_score") Integer reportTotalScore,
        @JsonProperty("created_at") Instant createdAt) {
}
