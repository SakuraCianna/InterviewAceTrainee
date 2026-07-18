package icu.sakuracianna.mianba.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeIndexSynchronizerTest {
    private static final String MANIFEST_KEY = "mianba:knowledge:manifest";

    @Test
    void synchronizationUpdatesChangedDocumentsAndRemovesStaleIdsAcrossCorpusVersions() {
        List<PublicKnowledgeDocument> documents = corpus();
        PublicKnowledgeDocument unchanged = documents.getFirst();
        String firstChunkText = unchanged.title() + "\n" + unchanged.content().split("(?m)(?=^## )")[0].strip();
        String unchangedHash = KnowledgeDocumentLoader.sha256(
                "local-model:384:" + unchanged.contentHash() + ':'
                        + KnowledgeDocumentLoader.sha256(firstChunkText));
        Map<Object, Object> manifest = new LinkedHashMap<>();
        manifest.put(unchanged.id() + "#0", unchangedHash);
        manifest.put("job-removed-in-previous-version", "old-hash");

        VectorStore vectorStore = mock(VectorStore.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("vectorStore", vectorStore);
        KnowledgeDocumentLoader loader = mock(KnowledgeDocumentLoader.class);
        when(loader.load(anyString())).thenReturn(documents);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(MANIFEST_KEY)).thenReturn(manifest);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        KnowledgeIndexSynchronizer synchronizer = new KnowledgeIndexSynchronizer(
                properties(), loader, beans.getBeanProvider(VectorStore.class), redis, jdbc);
        synchronizer.synchronize();

        verify(vectorStore).delete(List.of("job-removed-in-previous-version"));
        verify(hashes).delete(eq(MANIFEST_KEY), any(Object[].class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> batches = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, org.mockito.Mockito.atLeastOnce()).add(batches.capture());
        assertThat(batches.getAllValues().stream().flatMap(List::stream).toList())
                .hasSize(documents.size() * 3 - 1)
                .noneMatch(document -> document.getId().equals(unchanged.id() + "#0"))
                .allSatisfy(document -> assertThat(document.getText().codePointCount(
                        0, document.getText().length())).isLessThanOrEqualTo(260));
        ArgumentCaptor<String> stateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(stateSql.capture(), any(Object[].class));
        assertThat(stateSql.getAllValues()).anyMatch(sql -> sql.contains("status = 'READY'"));
    }

    @Test
    void missingVectorStoreMarksIndexFailedWithoutThrowingIntoApplicationStartup() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        KnowledgeIndexSynchronizer synchronizer = new KnowledgeIndexSynchronizer(
                properties(), mock(KnowledgeDocumentLoader.class),
                beans.getBeanProvider(VectorStore.class), mock(StringRedisTemplate.class), jdbc);

        synchronizer.synchronize();

        ArgumentCaptor<String> stateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(stateSql.capture(), any(Object[].class));
        assertThat(stateSql.getValue()).contains("status = 'FAILED'");
    }

    private static KnowledgeProperties properties() {
        return new KnowledgeProperties(
                true, true, "classpath*:knowledge-base/**/*.md", "2026.08",
                "local-model", 384, 5, 0.35);
    }

    private static List<PublicKnowledgeDocument> corpus() {
        List<PublicKnowledgeDocument> documents = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            documents.add(document("job-test-" + index, KnowledgeDomain.JOB, "job-content-" + index));
        }
        for (int index = 0; index < 30; index++) {
            documents.add(document(
                    "postgraduate-test-" + index,
                    KnowledgeDomain.POSTGRADUATE,
                    "postgraduate-content-" + index));
        }
        return List.copyOf(documents);
    }

    private static PublicKnowledgeDocument document(String id, KnowledgeDomain domain, String content) {
        String body = "## 核心知识与表达\n" + content
                + "\n## 研究与实践问题\n" + content + "-practice"
                + "\n## 综合评价\n" + content + "-evaluation";
        return new PublicKnowledgeDocument(
                id, domain, "test", id, List.of(id), List.of("test"),
                domain == KnowledgeDomain.POSTGRADUATE ? "representative" : "not-applicable",
                "2026.08", List.of("https://example.com/source"), body,
                KnowledgeDocumentLoader.sha256(body));
    }
}
