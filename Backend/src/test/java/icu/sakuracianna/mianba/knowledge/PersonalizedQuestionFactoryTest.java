package icu.sakuracianna.mianba.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalizedQuestionFactoryTest {
    private final PersonalizedQuestionFactory factory = new PersonalizedQuestionFactory();

    @Test
    void openingQuestionUsesBoundedPublicRagContentInsteadOfOnlyTheTitle() {
        String publicContent = "销售需求发现\n## 研究与实践问题\n"
                + "场景题：客户提出降价时，先确认业务目标、决策链、替代方案和成功指标。\n"
                + "不应把用户简历或岗位全文复制到问题中。";

        String question = factory.openingQuestion(
                KnowledgeDomain.JOB,
                List.of(new KnowledgeSnippet(
                        "job-sales#2", "销售需求发现", "sales", publicContent, 0.91)));

        assertThat(question)
                .contains("销售需求发现")
                .contains("业务目标、决策链、替代方案和成功指标")
                .doesNotContain("不应把用户简历")
                .hasSizeLessThan(360);
    }

    @Test
    void emptyRetrievalUsesStableDomainFallback() {
        assertThat(factory.openingQuestion(KnowledgeDomain.POSTGRADUATE, List.of()))
                .contains("专业基础", "报考动机");
    }
}
