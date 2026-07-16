package icu.sakuracianna.mianba.interview.web;

import icu.sakuracianna.mianba.interview.packageflow.JobInterviewPlan;
import icu.sakuracianna.mianba.interview.packageflow.JobInterviewStage;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 无数据库依赖的产品目录，费用表示训练次数而非在线支付金额。 */
@RestController
@RequestMapping("/api/interview-products")
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class InterviewProductController {
    private static final JobInterviewPlan JOB_PLAN = JobInterviewPlan.chineseEnterpriseV1();
    private static final List<InterviewProductStage> JOB_STAGES = JOB_PLAN.stages().stream()
            .map(stage -> new InterviewProductStage(
                    stage.code().name(),
                    stageName(stage.code()),
                    stage.code().sequence(),
                    stage.minTurns(),
                    stage.maxTurns(),
                    stage.targetMinutes()))
            .toList();
    private static final List<InterviewProduct> PRODUCTS = List.of(
            new InterviewProduct(
                    "job",
                    "求职面试",
                    3,
                    JOB_PLAN.stages().stream().mapToInt(JobInterviewPlan.StagePlan::maxTurns).sum(),
                    "两轮技术面 + HR 综合面，覆盖岗位能力与综合胜任力",
                    true,
                    JOB_STAGES),
            new InterviewProduct(
                    "postgraduate", "考研复试", 1, 5,
                    "专业基础、研究动机与表达训练", false, List.of()),
            new InterviewProduct(
                    "civil_service", "公考结构化", 1, 5,
                    "综合分析、组织协调与应急表达", false, List.of()),
            new InterviewProduct(
                    "ielts", "雅思口语", 2, 6,
                    "Part 1-3 英语口语模拟", false, List.of()));

    /** 返回当前可用的训练产品目录。 */
    @GetMapping
    public List<InterviewProduct> list() {
        return PRODUCTS;
    }

    /**
     * 查询指定训练产品。
     *
     * @throws ApiException 产品标识不存在时抛出
     */
    @GetMapping("/{productId}")
    public InterviewProduct get(@PathVariable String productId) {
        return PRODUCTS.stream().filter(product -> product.id().equals(productId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "interview_product_not_found", "训练产品不存在"));
    }

    private static String stageName(JobInterviewStage stage) {
        return switch (stage) {
            case TECHNICAL_FIRST -> "技术一面";
            case TECHNICAL_SECOND -> "技术二面";
            case HR_FINAL -> "HR 面";
        };
    }

    /** 前端展示和创建面试所需的产品元数据。 */
    public record InterviewProduct(
            String id,
            String name,
            int credits,
            int turns,
            String description,
            boolean packageRequired,
            List<InterviewProductStage> stages) {
        public InterviewProduct {
            stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        }
    }

    /** 工作面试套餐单阶段的展示投影。 */
    public record InterviewProductStage(
            String code,
            String name,
            int sequence,
            int minTurns,
            int maxTurns,
            int targetDurationMinutes) {
    }
}
