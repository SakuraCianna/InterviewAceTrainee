package icu.sakuracianna.mianba.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 用于启动门禁的数据库、Redis 与 RabbitMQ 连接配置镜像。 */
@ConfigurationProperties("mianba.infrastructure")
public record InfrastructureProperties(
        String databaseUrl,
        String databaseUsername,
        String databasePassword,
        String redisHost,
        String rabbitHost,
        String rabbitUsername,
        String rabbitPassword) {
}
