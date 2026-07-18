package icu.sakuracianna.mianba.knowledge;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.observation.DefaultEmbeddingModelObservationConvention;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationDocumentation;
import org.springframework.ai.observation.conventions.AiProvider;
import org.springframework.ai.transformers.ResourceCacheService;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** 使用纯 Java 池化，避免为 ONNX 推理额外加载通用 DJL 数组引擎。 */
final class JavaPoolingTransformersEmbeddingModel extends TransformersEmbeddingModel {

    private static final EmbeddingModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
            new DefaultEmbeddingModelObservationConvention();

    private final ObservationRegistry observationRegistry;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private Map<String, String> tokenizerOptions = Map.of();
    private Resource tokenizerResource =
            new DefaultResourceLoader().getResource(DEFAULT_ONNX_TOKENIZER_URI);
    private Resource modelResource =
            new DefaultResourceLoader().getResource(DEFAULT_ONNX_MODEL_URI);
    private String modelOutputName = DEFAULT_MODEL_OUTPUT_NAME;
    private String resourceCacheDirectory;
    private boolean disableCaching;
    private int gpuDeviceId = -1;
    private HuggingFaceTokenizer tokenizer;
    private OrtSession session;
    private Set<String> onnxModelInputs = Set.of();
    private EmbeddingModelObservationConvention observationConvention =
            DEFAULT_OBSERVATION_CONVENTION;

    JavaPoolingTransformersEmbeddingModel(
            MetadataMode metadataMode, ObservationRegistry observationRegistry) {
        super(metadataMode, observationRegistry);
        this.observationRegistry = observationRegistry;
    }

    @Override
    public void setTokenizerOptions(Map<String, String> tokenizerOptions) {
        this.tokenizerOptions = Map.copyOf(tokenizerOptions);
    }

    @Override
    public void setDisableCaching(boolean disableCaching) {
        this.disableCaching = disableCaching;
    }

    @Override
    public void setResourceCacheDirectory(String resourceCacheDirectory) {
        this.resourceCacheDirectory = resourceCacheDirectory;
    }

    @Override
    public void setGpuDeviceId(int gpuDeviceId) {
        this.gpuDeviceId = gpuDeviceId;
    }

    @Override
    public void setTokenizerResource(Resource tokenizerResource) {
        this.tokenizerResource = tokenizerResource;
    }

    @Override
    public void setModelResource(Resource modelResource) {
        this.modelResource = modelResource;
    }

    @Override
    public void setTokenizerResource(String tokenizerResourceUri) {
        this.tokenizerResource = new DefaultResourceLoader().getResource(tokenizerResourceUri);
    }

    @Override
    public void setModelResource(String modelResourceUri) {
        this.modelResource = new DefaultResourceLoader().getResource(modelResourceUri);
    }

    @Override
    public void setModelOutputName(String modelOutputName) {
        this.modelOutputName = modelOutputName;
    }

    @Override
    public void setObservationConvention(
            EmbeddingModelObservationConvention observationConvention) {
        Assert.notNull(observationConvention, "observationConvention cannot be null");
        this.observationConvention = observationConvention;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        ResourceCacheService cacheService = StringUtils.hasText(this.resourceCacheDirectory)
                ? new ResourceCacheService(this.resourceCacheDirectory)
                : new ResourceCacheService();
        Resource cachedTokenizer = this.disableCaching
                ? this.tokenizerResource
                : cacheService.getCachedResource(this.tokenizerResource);
        Resource cachedModel = this.disableCaching
                ? this.modelResource
                : cacheService.getCachedResource(this.modelResource);
        this.tokenizer = HuggingFaceTokenizer.newInstance(
                cachedTokenizer.getInputStream(), this.tokenizerOptions);

        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(2);
            // 4 GB 单机优先保证索引期间内存可预测，避免 ONNX arena 长期占满容器余量。
            options.setCPUArenaAllocator(false);
            options.setMemoryPatternOptimization(false);
            if (this.gpuDeviceId >= 0) {
                options.addCUDA(this.gpuDeviceId);
            }
            this.session = this.environment.createSession(
                    cachedModel.getContentAsByteArray(), options);
        }
        this.onnxModelInputs = this.session.getInputNames();
        Set<String> outputs = this.session.getOutputNames();
        Assert.isTrue(
                outputs.contains(this.modelOutputName),
                "ONNX output names do not contain expected output: " + this.modelOutputName);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingModelObservationContext context = EmbeddingModelObservationContext.builder()
                .embeddingRequest(request)
                .provider(AiProvider.ONNX.value())
                .build();
        return EmbeddingModelObservationDocumentation.EMBEDDING_MODEL_OPERATION
                .observation(
                        this.observationConvention,
                        DEFAULT_OBSERVATION_CONVENTION,
                        () -> context,
                        this.observationRegistry)
                .observe(() -> embedObserved(request, context));
    }

    private EmbeddingResponse embedObserved(
            EmbeddingRequest request, EmbeddingModelObservationContext context) {
        try {
            Encoding[] encodings = this.tokenizer.batchEncode(request.getInstructions());
            long[][] inputIds = new long[encodings.length][];
            long[][] attentionMask = new long[encodings.length][];
            long[][] tokenTypeIds = new long[encodings.length][];
            for (int index = 0; index < encodings.length; index++) {
                inputIds[index] = encodings[index].getIds();
                attentionMask[index] = encodings[index].getAttentionMask();
                tokenTypeIds[index] = encodings[index].getTypeIds();
            }

            List<float[]> embeddings;
            try (OnnxTensor idsTensor = OnnxTensor.createTensor(this.environment, inputIds);
                    OnnxTensor maskTensor =
                            OnnxTensor.createTensor(this.environment, attentionMask);
                    OnnxTensor typesTensor =
                            OnnxTensor.createTensor(this.environment, tokenTypeIds)) {
                Map<String, OnnxTensor> inputs = Map.of(
                                "input_ids", idsTensor,
                                "attention_mask", maskTensor,
                                "token_type_ids", typesTensor)
                        .entrySet().stream()
                        .filter(entry -> this.onnxModelInputs.contains(entry.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                try (OrtSession.Result results = this.session.run(inputs)) {
                    OnnxValue output = results.get(this.modelOutputName).orElseThrow();
                    embeddings = meanPooling((float[][][]) output.getValue(), attentionMask);
                }
            }

            AtomicInteger index = new AtomicInteger();
            EmbeddingResponse response = new EmbeddingResponse(embeddings.stream()
                    .map(vector -> new Embedding(vector, index.incrementAndGet()))
                    .toList());
            context.setResponse(response);
            return response;
        } catch (OrtException exception) {
            throw new IllegalStateException("Local ONNX embedding failed", exception);
        }
    }

    static List<float[]> meanPooling(float[][][] tokenEmbeddings, long[][] attentionMask) {
        Assert.isTrue(
                tokenEmbeddings.length == attentionMask.length,
                "Embedding and attention-mask batch sizes must match");
        List<float[]> result = new ArrayList<>(tokenEmbeddings.length);
        for (int batch = 0; batch < tokenEmbeddings.length; batch++) {
            Assert.isTrue(
                    tokenEmbeddings[batch].length == attentionMask[batch].length,
                    "Embedding and attention-mask sequence lengths must match");
            int dimensions = tokenEmbeddings[batch][0].length;
            float[] pooled = new float[dimensions];
            long weightSum = 0;
            for (int token = 0; token < tokenEmbeddings[batch].length; token++) {
                long weight = attentionMask[batch][token];
                weightSum += weight;
                for (int dimension = 0; dimension < dimensions; dimension++) {
                    pooled[dimension] += tokenEmbeddings[batch][token][dimension] * weight;
                }
            }
            float divisor = Math.max(weightSum, 1.0e-9f);
            for (int dimension = 0; dimension < dimensions; dimension++) {
                pooled[dimension] /= divisor;
            }
            result.add(pooled);
        }
        return result;
    }

    @PreDestroy
    void closeResources() throws Exception {
        closeResources(this.session, this.tokenizer);
    }

    static void closeResources(AutoCloseable... resources) throws Exception {
        Exception failure = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
