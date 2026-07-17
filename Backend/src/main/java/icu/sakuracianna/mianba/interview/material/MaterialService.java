package icu.sakuracianna.mianba.interview.material;

import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/** 负责材料校验、文本提取与幂等持久化，不保存用户上传的原始文件。 */
public interface MaterialService {
    /**
     * 校验、解析并幂等保存面试材料。
     * 原始上传文件不得写入持久化存储，解析结果只能保留业务所需字段。
     *
     * @return 可供创建面试引用的材料视图
     */
    MaterialView upload(
            UUID userId,
            String idempotencyKey,
            String interviewType,
            MultipartFile resumeFile,
            String jobTitle,
            String jobRequirements,
            String targetSchool,
            String major,
            String researchDirection);

    /**
     * 返回指定用户和面试类型最新的 ready 状态材料；不存在时返回 null。
     */
    MaterialView getLatestByType(UUID userId, String interviewType);
}
