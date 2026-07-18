package icu.sakuracianna.mianba.knowledge;

import java.util.Arrays;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.model.transformers.autoconfigure.TransformersEmbeddingModelProperties;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.util.Assert;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;

/** 为启用 ACL 的 Redis 构造包含用户名的 Spring AI 向量客户端。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mianba.knowledge", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({
    RedisVectorStoreProperties.class,
    TransformersEmbeddingModelProperties.class
})
public class AclRedisVectorStoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransformersEmbeddingModel.class)
    TransformersEmbeddingModel localEmbeddingModel(
            TransformersEmbeddingModelProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<EmbeddingModelObservationConvention> observationConvention) {
        JavaPoolingTransformersEmbeddingModel model =
                new JavaPoolingTransformersEmbeddingModel(
                        properties.getMetadataMode(),
                        observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
        model.setDisableCaching(!properties.getCache().isEnabled());
        model.setResourceCacheDirectory(properties.getCache().getDirectory());
        model.setTokenizerResource(properties.getTokenizer().getUri());
        model.setTokenizerOptions(properties.getTokenizer().getOptions());
        model.setModelResource(properties.getOnnx().getModelUri());
        model.setGpuDeviceId(properties.getOnnx().getGpuDeviceId());
        model.setModelOutputName(properties.getOnnx().getModelOutputName());
        observationConvention.ifAvailable(model::setObservationConvention);
        return model;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(VectorStore.class)
    RedisClient knowledgeRedisClient(JedisConnectionFactory connectionFactory) {
        return RedisClient.builder()
                .hostAndPort(connectionFactory.getHostName(), connectionFactory.getPort())
                .clientConfig(clientConfig(connectionFactory))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    RedisVectorStore aclRedisVectorStore(
            RedisClient knowledgeRedisClient,
            EmbeddingModel embeddingModel,
            RedisVectorStoreProperties properties) {
        RedisVectorStoreProperties.HnswProperties hnsw = properties.getHnsw();
        return RedisVectorStore.builder(knowledgeRedisClient, embeddingModel)
                .initializeSchema(properties.isInitializeSchema())
                .indexName(properties.getIndexName())
                .prefix(properties.getPrefix())
                .vectorAlgorithm(RedisVectorStore.Algorithm.HNSW)
                .distanceMetric(RedisVectorStore.DistanceMetric.COSINE)
                .metadataFields(RedisVectorStore.MetadataField.tag("domain"))
                .hnswM(hnsw.getM())
                .hnswEfConstruction(hnsw.getEfConstruction())
                .hnswEfRuntime(hnsw.getEfRuntime())
                .batchingStrategy(new TokenCountBatchingStrategy())
                .build();
    }

    static DefaultJedisClientConfig clientConfig(JedisConnectionFactory connectionFactory) {
        RedisStandaloneConfiguration standalone = connectionFactory.getStandaloneConfiguration();
        Assert.state(standalone != null, "Public knowledge Redis must use standalone configuration");
        Assert.hasText(standalone.getUsername(), "Public knowledge Redis ACL username is required");

        RedisPassword redisPassword = standalone.getPassword();
        Assert.state(redisPassword.isPresent(), "Public knowledge Redis ACL password is required");
        char[] password = redisPassword.get();
        try {
            return DefaultJedisClientConfig.builder()
                    .ssl(connectionFactory.isUseSsl())
                    .clientName(connectionFactory.getClientName())
                    .timeoutMillis(connectionFactory.getTimeout())
                    .user(standalone.getUsername())
                    .password(new String(password))
                    .build();
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
