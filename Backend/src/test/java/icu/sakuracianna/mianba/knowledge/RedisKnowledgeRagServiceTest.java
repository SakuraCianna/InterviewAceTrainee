package icu.sakuracianna.mianba.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class RedisKnowledgeRagServiceTest {
    @Test
    void retrievalEnforcesDomainFilterAndReturnsOnlyPublicDocumentProjection() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document document = Document.builder()
                .id("job-sales-discovery")
                .text("销售需求发现应先确认业务目标、决策链和成功指标。")
                .metadata(Map.of("domain", "job", "category", "sales", "title", "销售需求发现"))
                .score(0.88)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("vectorStore", vectorStore);
        KnowledgeProperties properties = new KnowledgeProperties(
                true, true, "classpath*:knowledge-base/**/*.md", "2026.07",
                "local-model", 384, 5, 0.35);
        RedisKnowledgeRagService service = new RedisKnowledgeRagService(
                beans.getBeanProvider(VectorStore.class), properties, mock(JdbcTemplate.class));

        List<KnowledgeSnippet> results = service.retrieve("企业销售如何做需求发现", KnowledgeDomain.JOB);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.documentId()).isEqualTo("job-sales-discovery");
            assertThat(result.category()).isEqualTo("sales");
            assertThat(result.content()).doesNotContain("resume", "material_context");
        });
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getFilterExpression().toString()).contains("domain", "job");
    }
}
