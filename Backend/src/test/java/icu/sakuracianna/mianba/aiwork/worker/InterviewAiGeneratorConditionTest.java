package icu.sakuracianna.mianba.aiwork.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InterviewAiGeneratorConditionTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(
                    SpringAiInterviewGenerator.class,
                    DeterministicInterviewAiGenerator.class)
            .withPropertyValues("mianba.runtime.role=worker");

    @Test
    void defaultWorkerUsesOnlySpringAiGenerator() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(InterviewAiGenerator.class);
            assertThat(context).hasSingleBean(SpringAiInterviewGenerator.class);
            assertThat(context).doesNotHaveBean(DeterministicInterviewAiGenerator.class);
        });
    }

    @Test
    void nonProductionStubWorkerUsesOnlyDeterministicGenerator() {
        contextRunner.withPropertyValues(
                        "mianba.runtime.production=false",
                        "mianba.ai-runtime.stub-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(InterviewAiGenerator.class);
                    assertThat(context).hasSingleBean(DeterministicInterviewAiGenerator.class);
                    assertThat(context).doesNotHaveBean(SpringAiInterviewGenerator.class);
                });
    }

    @Test
    void productionStubCannotCreateEitherGenerator() {
        contextRunner.withPropertyValues(
                        "mianba.runtime.production=true",
                        "mianba.ai-runtime.stub-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(InterviewAiGenerator.class));
    }
}
