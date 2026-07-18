# syntax=docker/dockerfile:1.7

# CI 在隔离 builder 中编译；src/test 已由 .dockerignore 排除，测试在镜像构建前独立执行。
# 项目只提供 Windows Maven Wrapper，Linux 构建因此使用官方 Maven + Temurin 21 builder。
FROM maven:3.9.16-eclipse-temurin-21-alpine@sha256:d88e5b38297858f65f97bc7e7964c760ab988fd18ace41589176f1468c49a489 AS build
WORKDIR /workspace
ARG EMBEDDING_MODEL_REVISION=e8f8c211226b894fcb81acc59f3b34ba3efd5f42
ARG EMBEDDING_MODEL_SHA256=98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f
ARG EMBEDDING_TOKENIZER_SHA256=2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8
RUN apk add --no-cache curl \
    && mkdir -p /opt/mianba-model \
    && curl --fail --location --retry 3 \
      "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/${EMBEDDING_MODEL_REVISION}/onnx/model_quint8_avx2.onnx" \
      --output /opt/mianba-model/model.onnx \
    && curl --fail --location --retry 3 \
      "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/${EMBEDDING_MODEL_REVISION}/tokenizer.json" \
      --output /opt/mianba-model/tokenizer.json \
    && echo "${EMBEDDING_MODEL_SHA256}  /opt/mianba-model/model.onnx" | sha256sum -c - \
    && echo "${EMBEDDING_TOKENIZER_SHA256}  /opt/mianba-model/tokenizer.json" | sha256sum -c -
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src/main src/main
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

# 最终层只包含 JRE、健康检查工具与可执行 JAR，不包含源码或测试文件。
FROM eclipse-temurin:21.0.11_10-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 mianba \
    && useradd --system --uid 10001 --gid 10001 --no-create-home \
      --home-dir /nonexistent --shell /usr/sbin/nologin mianba
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/mianba-backend-*.jar /app/app.jar
COPY --from=build --chown=10001:10001 /opt/mianba-model /opt/mianba-model
USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
