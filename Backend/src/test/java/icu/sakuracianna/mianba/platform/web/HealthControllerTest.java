package icu.sakuracianna.mianba.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import icu.sakuracianna.mianba.interview.material.MaterialParserClient;
import icu.sakuracianna.mianba.knowledge.KnowledgeDomain;
import icu.sakuracianna.mianba.knowledge.KnowledgeRagService;
import icu.sakuracianna.mianba.knowledge.KnowledgeSnippet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {

    @Test
    void interviewCoreRequiresEmbeddingCoverageAndBothDomainRetrievalProbes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> state = new HashMap<>();
        state.put("status", "READY");
        state.put("corpus_version", "2026.07.1");
        state.put("model_id", "local-model");
        state.put("vector_dimensions", 384);
        state.put("document_count", 239);
        state.put("job_document_count", 200);
        state.put("postgraduate_document_count", 39);
        state.put("chunk_count", 956);
        state.put("indexed_chunk_count", 956);
        state.put("failure_count", 0);
        when(jdbc.queryForMap(contains("knowledge_index_state"))).thenReturn(state);
        when(jdbc.queryForObject(contains("providers"), eq(Long.class))).thenReturn(1L);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("vectorStore", mock(VectorStore.class));
        KnowledgeRagService knowledge = mock(KnowledgeRagService.class);
        when(knowledge.retrieve(anyString(), any(KnowledgeDomain.class))).thenReturn(List.of(
                new KnowledgeSnippet("public#0", "公开知识", "test", "公开内容", 0.9)));
        HealthController controller = new HealthController(
                jdbc, mock(StringRedisTemplate.class), mock(RabbitTemplate.class),
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC),
                mock(MaterialParserClient.class), beans.getBeanProvider(VectorStore.class), knowledge);

        Map<String, Object> result = controller.interviewCore();

        assertThat(result)
                .containsEntry("public_knowledge_rag_ready", true)
                .containsEntry("persistent_user_vector_store_enabled", false)
                .containsEntry("embedding_provider_available", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> cards = (Map<String, Object>) result.get("capability_cards");
        @SuppressWarnings("unchecked")
        Map<String, Object> recall = (Map<String, Object>) result.get("recall_quality");
        assertThat(cards)
                .containsEntry("job_document_count", 200L)
                .containsEntry("postgraduate_document_count", 39L);
        assertThat(recall)
                .containsEntry("job_probe_ready", true)
                .containsEntry("postgraduate_probe_ready", true);
    }

    @Test
    void returnsParserReadyWhenEveryRequiredDependencyIsReady() throws Exception {
        MaterialParserClient parser = mock(MaterialParserClient.class);
        when(parser.isReady()).thenReturn(true);
        MockMvc mvc = mvc(parser);

        mvc.perform(get("/api/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.database_ready").value(true))
                .andExpect(jsonPath("$.redis_ready").value(true))
                .andExpect(jsonPath("$.rabbit_ready").value(true))
                .andExpect(jsonPath("$.worker_ready").value(true))
                .andExpect(jsonPath("$.parser_ready").value(true));
    }

    @Test
    void returnsUnavailableWhenMaterialParserIsNotReady() throws Exception {
        MaterialParserClient parser = mock(MaterialParserClient.class);
        when(parser.isReady()).thenReturn(false);
        MockMvc mvc = mvc(parser);

        mvc.perform(get("/api/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("material_parser_not_ready"));
    }

    @Test
    void hidesMaterialParserFailureDetailsFromReadinessResponse() throws Exception {
        MaterialParserClient parser = mock(MaterialParserClient.class);
        when(parser.isReady()).thenThrow(new IllegalStateException("internal-token-must-not-leak"));
        MockMvc mvc = mvc(parser);

        mvc.perform(get("/api/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("material_parser_not_ready"))
                .andExpect(jsonPath("$.message").value("材料解析服务尚未就绪"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static MockMvc mvc(MaterialParserClient parser) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbc.queryForObject(contains("runtime_heartbeats"), eq(Boolean.class))).thenReturn(true);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(false).when(redis).hasKey(any(String.class));
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doReturn(true).when(rabbit).execute(any(ChannelCallback.class));
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        HealthController controller = new HealthController(
                jdbc, redis, rabbit, clock, parser,
                beans.getBeanProvider(VectorStore.class), mock(KnowledgeRagService.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
