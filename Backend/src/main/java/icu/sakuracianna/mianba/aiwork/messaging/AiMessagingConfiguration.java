package icu.sakuracianna.mianba.aiwork.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 声明持久化 Exchange、Quorum 主队列和 DLQ 绑定；数据库 Outbox 负责延迟重试。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class AiMessagingConfiguration {
    @Bean
    DirectExchange aiExchange() {
        return new DirectExchange(AiMessagingTopology.EXCHANGE, true, false);
    }

    @Bean
    DirectExchange aiDeadLetterExchange() {
        return new DirectExchange(AiMessagingTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue aiJobQueue() {
        return QueueBuilder.durable(AiMessagingTopology.JOB_QUEUE)
                .quorum()
                .maxLength(1000L)
                .withArgument("x-overflow", "reject-publish")
                .deadLetterExchange(AiMessagingTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(AiMessagingTopology.DEAD_LETTER_ROUTING_KEY)
                .withArgument("x-delivery-limit", 5)
                .build();
    }

    @Bean
    Queue aiDeadLetterQueue() {
        return QueueBuilder.durable(AiMessagingTopology.DEAD_LETTER_QUEUE)
                .quorum()
                .maxLength(2000L)
                .build();
    }

    @Bean
    Binding aiJobBinding(Queue aiJobQueue, DirectExchange aiExchange) {
        return BindingBuilder.bind(aiJobQueue).to(aiExchange).with(AiMessagingTopology.ROUTING_KEY);
    }

    @Bean
    Binding aiDeadLetterBinding(Queue aiDeadLetterQueue, DirectExchange aiDeadLetterExchange) {
        return BindingBuilder.bind(aiDeadLetterQueue)
                .to(aiDeadLetterExchange)
                .with(AiMessagingTopology.DEAD_LETTER_ROUTING_KEY);
    }
}
