package icu.sakuracianna.mianba.interview.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import icu.sakuracianna.mianba.aiwork.service.TaskView;
import java.time.Instant;
import java.util.UUID;

/** 当前面试状态的只读 API 投影。 */
public record InterviewView(
        @JsonProperty("session_id") UUID id,
        @JsonProperty("interview_type") String interviewType,
        String status,
        @JsonProperty("current_step_index") int currentTurnIndex,
        @JsonProperty("total_steps") int totalTurns,
        @JsonProperty("current_question") Question currentQuestion,
        @JsonProperty("active_task") TaskView activeTask,
        Object report,
        @JsonProperty("updated_at") Instant updatedAt) {

    /** 在不改变会话业务字段的前提下附加刷新恢复所需的当前任务。 */
    public InterviewView withActiveTask(TaskView task) {
        return new InterviewView(
                id, interviewType, status, currentTurnIndex, totalTurns,
                currentQuestion, task, report, updatedAt);
    }

    /** 当前轮次问题投影，不包含模型提示词或材料原文。 */
    public record Question(
            @JsonProperty("turn_index") int turnIndex,
            @JsonProperty("round_name") String roundName,
            String text) {
    }
}
