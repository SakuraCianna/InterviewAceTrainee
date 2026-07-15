package icu.sakuracianna.mianba.interview.web;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.util.List;
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
    private static final List<InterviewProduct> PRODUCTS = List.of(
            new InterviewProduct("job", "求职面试", 2, 6, "围绕岗位经历进行递进追问"),
            new InterviewProduct("postgraduate", "考研复试", 1, 5, "专业基础、研究动机与表达训练"),
            new InterviewProduct("civil_service", "公考结构化", 1, 5, "综合分析、组织协调与应急表达"),
            new InterviewProduct("ielts", "雅思口语", 2, 6, "Part 1-3 英语口语模拟"));

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

    /** 前端展示和创建面试所需的产品元数据。 */
    public record InterviewProduct(String id, String name, int credits, int turns, String description) {
    }
}
