package icu.sakuracianna.mianba.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本地公共知识库、ONNX 嵌入和 Redis 向量索引配置。 */
@ConfigurationProperties("mianba.knowledge")
public record KnowledgeProperties(
        boolean enabled,
        boolean synchronizeOnStartup,
        String corpusLocation,
        String corpusVersion,
        String modelId,
        int vectorDimensions,
        int topK,
        double similarityThreshold) {

    public KnowledgeProperties {
        corpusLocation = required(corpusLocation, "corpusLocation");
        corpusVersion = required(corpusVersion, "corpusVersion");
        modelId = required(modelId, "modelId");
        if (vectorDimensions < 1 || vectorDimensions > 4096) {
            throw new IllegalArgumentException("vectorDimensions must be between 1 and 4096");
        }
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
        if (similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException("similarityThreshold must be between 0 and 1");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
