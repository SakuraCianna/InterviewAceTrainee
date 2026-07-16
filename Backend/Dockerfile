# syntax=docker/dockerfile:1.7

# CI 在隔离 builder 中编译；src/test 已由 .dockerignore 排除，测试在镜像构建前独立执行。
# 项目只提供 Windows Maven Wrapper，Linux 构建因此使用官方 Maven + Temurin 21 builder。
FROM maven:3.9.16-eclipse-temurin-21-alpine@sha256:d88e5b38297858f65f97bc7e7964c760ab988fd18ace41589176f1468c49a489 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src/main src/main
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

# 最终层只包含 JRE、健康检查工具与可执行 JAR，不包含源码或测试文件。
FROM eclipse-temurin:21.0.11_10-jre-alpine-3.23@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c
RUN apk add --no-cache curl \
    && addgroup -S -g 10001 mianba \
    && adduser -S -D -H -u 10001 -G mianba mianba
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/mianba-backend-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
