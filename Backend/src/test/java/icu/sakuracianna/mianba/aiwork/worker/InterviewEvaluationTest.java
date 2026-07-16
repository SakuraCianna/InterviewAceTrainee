package icu.sakuracianna.mianba.aiwork.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterviewEvaluationTest {

    @Test
    void constructorDefensivelyCopiesEveryModelList() {
        List<DimensionEvaluation> dimensions = new ArrayList<>(List.of(
                new DimensionEvaluation(
                        "SYSTEM_DESIGN", 80, "选择缓存降低延迟", "需要说明失效策略")));
        List<String> sections = new ArrayList<>(List.of("SYSTEM_DESIGN"));
        List<String> topics = new ArrayList<>(List.of("CACHE_CONSISTENCY"));
        List<String> risks = new ArrayList<>(List.of("CAPACITY_EVIDENCE_MISSING"));

        InterviewEvaluation evaluation = new InterviewEvaluation(
                80,
                "回答给出了取舍，但容量依据不足。",
                dimensions,
                sections,
                topics,
                risks,
                false,
                "缓存失效时如何保证一致性？",
                "SYSTEM_DESIGN",
                "SYSTEM_DESIGN",
                "CACHE_CONSISTENCY");
        dimensions.clear();
        sections.clear();
        topics.clear();
        risks.clear();

        assertThat(evaluation.dimensions()).hasSize(1);
        assertThat(evaluation.coveredSections()).containsExactly("SYSTEM_DESIGN");
        assertThat(evaluation.coveredTopics()).containsExactly("CACHE_CONSISTENCY");
        assertThat(evaluation.riskFlags()).containsExactly("CAPACITY_EVIDENCE_MISSING");
        assertThat(evaluation.dimensions()).isUnmodifiable();
        assertThat(evaluation.coveredSections()).isUnmodifiable();
    }

    @Test
    void compatibilityRoundNameIsDerivedFromTrustedNextSection() {
        InterviewEvaluation evaluation = new InterviewEvaluation(
                75,
                "回答有具体证据。",
                List.of(new DimensionEvaluation(
                        "FOUNDATIONS", 75, "解释了核心机制", "边界条件可以更完整")),
                List.of("FOUNDATIONS"),
                List.of("JVM_MEMORY"),
                List.of(),
                false,
                "请说明垃圾回收的主要权衡。",
                "FOUNDATIONS",
                "FOUNDATIONS",
                "JVM_GC");

        assertThat(evaluation.roundName()).isEqualTo("FOUNDATIONS");
    }
}
