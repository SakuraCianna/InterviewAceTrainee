package icu.sakuracianna.mianba.interview.material;

import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/** 负责在单次创建请求内校验和解析材料，不提供任何持久化或恢复能力。 */
public interface MaterialService {
    /**
     * 校验并解析临时面试材料。调用方必须使用 try-with-resources 及时清理。
     *
     * @return 仅当前请求可用的可清理材料上下文
     */
    EphemeralMaterial analyze(
            UUID userId,
            String requestId,
            String interviewType,
            MultipartFile resumeFile,
            String jobTitle,
            String jobRequirements,
            String targetSchool,
            String major,
            String researchDirection);
}
