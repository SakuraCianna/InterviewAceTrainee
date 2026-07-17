package icu.sakuracianna.mianba.interview.material;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** 上传材料经过安全解析后返回给前端的脱敏视图。 */
public record MaterialView(
        UUID id,
        @JsonProperty("interview_type") String interviewType,
        @JsonProperty("job_title") String jobTitle,
        @JsonProperty("target_school") String targetSchool,
        String major,
        @JsonProperty("research_direction") String researchDirection,
        @JsonProperty("resume_text_preview") String resumeTextPreview,
        @JsonProperty("extracted_text_chars") int extractedTextChars,
        @JsonProperty("profile_summary") String profileSummary,
        List<String> keywords) {
}
