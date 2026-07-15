package icu.sakuracianna.mianba.interview.web;

import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.interview.material.MaterialService;
import icu.sakuracianna.mianba.interview.material.MaterialView;
import icu.sakuracianna.mianba.platform.web.ValidIdempotencyKey;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 接收有界面试材料，并返回不含原始文件的解析结果。 */
@Validated
@RestController
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class MaterialController {
    private final MaterialService materials;

    public MaterialController(MaterialService materials) {
        this.materials = materials;
    }

    /**
     * 上传并解析有界面试材料。
     * 原始文件仅在请求处理期间驻留内存，响应和持久化结果均不包含原始二进制内容。
     *
     * @return 已完成安全检查和结构化解析的材料视图
     */
    @PostMapping(path = "/api/interview-materials", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialView upload(
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @RequestParam("interview_type") @NotBlank String interviewType,
            @RequestParam(value = "resume_file", required = false) MultipartFile resumeFile,
            @RequestParam(value = "job_title", required = false) @Size(max = 160) String jobTitle,
            @RequestParam(value = "job_requirements", required = false) @Size(max = 8000) String jobRequirements,
            @RequestParam(value = "target_school", required = false) @Size(max = 160) String targetSchool,
            @RequestParam(value = "major", required = false) @Size(max = 160) String major,
            @RequestParam(value = "research_direction", required = false) @Size(max = 240) String researchDirection,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return materials.upload(principal.userId(), idempotencyKey, interviewType, resumeFile,
                jobTitle, jobRequirements, targetSchool, major, researchDirection);
    }
}
