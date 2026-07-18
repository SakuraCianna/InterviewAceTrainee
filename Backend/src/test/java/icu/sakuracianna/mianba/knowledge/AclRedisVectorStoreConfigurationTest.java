package icu.sakuracianna.mianba.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.DefaultJedisClientConfig;

class AclRedisVectorStoreConfigurationTest {

    @Test
    void backsOffWhenApplicationProvidesVectorStore() {
        VectorStore applicationVectorStore = mock(VectorStore.class);
        TransformersEmbeddingModel embeddingModel = mock(TransformersEmbeddingModel.class);

        new ApplicationContextRunner()
                .withUserConfiguration(AclRedisVectorStoreConfiguration.class)
                .withPropertyValues("mianba.knowledge.enabled=true")
                .withBean(VectorStore.class, () -> applicationVectorStore)
                .withBean(TransformersEmbeddingModel.class, () -> embeddingModel)
                .run(context -> {
                    assertThat(context).hasSingleBean(VectorStore.class);
                    assertThat(context.getBean(VectorStore.class))
                            .isSameAs(applicationVectorStore);
                    assertThat(context).doesNotHaveBean(redis.clients.jedis.RedisClient.class);
                });
    }

    @Test
    void propagatesAclUsernameAndPasswordToJedisClient() {
        JedisConnectionFactory connectionFactory = mock(JedisConnectionFactory.class);
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("redis", 6379);
        standalone.setUsername("mianba_app");
        standalone.setPassword("secret-value");
        when(connectionFactory.getStandaloneConfiguration()).thenReturn(standalone);
        when(connectionFactory.getTimeout()).thenReturn(2000);

        DefaultJedisClientConfig config =
                AclRedisVectorStoreConfiguration.clientConfig(connectionFactory);

        assertThat(config.getUser()).isEqualTo("mianba_app");
        assertThat(config.getPassword()).isEqualTo("secret-value");
        assertThat(config.getSocketTimeoutMillis()).isEqualTo(2000);
    }

    @Test
    void rejectsRedisConfigurationWithoutAclUsername() {
        JedisConnectionFactory connectionFactory = mock(JedisConnectionFactory.class);
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("redis", 6379);
        standalone.setPassword("secret-value");
        when(connectionFactory.getStandaloneConfiguration()).thenReturn(standalone);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AclRedisVectorStoreConfiguration.clientConfig(connectionFactory))
                .withMessageContaining("ACL username");
    }
}
