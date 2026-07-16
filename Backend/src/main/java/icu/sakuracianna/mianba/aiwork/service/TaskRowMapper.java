package icu.sakuracianna.mianba.aiwork.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 统一把任务查询结果转换为管理端与用户端共享的 TaskView。
 *
 * JSON 结果在此处集中解析并转换为 SQLException，避免不同查询入口对损坏数据产生不一致行为。
 */
final class TaskRowMapper {
    static final String COLUMNS = """
            id, session_id, kind, status, stage, progress, attempt, max_attempts,
            retryable, version, result_ref::text AS result_ref_json,
            error_code, error_message, created_at, updated_at
            """;

    private TaskRowMapper() {
    }

    static TaskView map(ResultSet resultSet, int rowNumber, ObjectMapper mapper) throws SQLException {
        String resultJson = resultSet.getString("result_ref_json");
        Object result = null;
        if (resultJson != null) {
            try {
                result = mapper.readTree(resultJson);
            } catch (JacksonException exception) {
                throw new SQLException("Invalid ai_jobs.result_ref JSON", exception);
            }
        }
        String errorCode = resultSet.getString("error_code");
        TaskView.TaskError error = errorCode == null
                ? null
                : new TaskView.TaskError(errorCode, resultSet.getString("error_message"));
        return new TaskView(
                resultSet.getObject("id", java.util.UUID.class),
                resultSet.getObject("session_id", java.util.UUID.class),
                resultSet.getString("kind"),
                resultSet.getString("status"),
                resultSet.getString("stage"),
                resultSet.getInt("progress"),
                resultSet.getInt("attempt"),
                resultSet.getInt("max_attempts"),
                resultSet.getBoolean("retryable"),
                resultSet.getLong("version"),
                result,
                error,
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
