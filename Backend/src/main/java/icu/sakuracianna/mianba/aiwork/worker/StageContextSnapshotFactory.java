package icu.sakuracianna.mianba.aiwork.worker;

import icu.sakuracianna.mianba.interview.packageflow.JobInterviewStage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 从已验证评价字段重建跨场上下文，不接受原始问答、报告或材料。 */
final class StageContextSnapshotFactory {
    private static final int MAX_DIMENSIONS = 8;
    private static final int MAX_CODES = 16;
    private static final int MAX_EVIDENCE_CODE_POINTS = 800;
    private static final int MAX_COMMENT_CODE_POINTS = 800;
    private static final int SNAPSHOT_BYTE_BUDGET = 40_960;
    private static final Pattern CODE_PATTERN =
            Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");
    private static final ObjectMapper JSON = new ObjectMapper();

    Map<String, Object> create(StageInput input) {
        Objects.requireNonNull(input, "input");
        List<Map<String, Object>> dimensions = normalizedDimensions(input.dimensions());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshot_version", "stage-context-v1");
        snapshot.put("source_stage", input.sourceStage().name());
        snapshot.put("stage_score", input.stageScore());
        snapshot.put("rubric_version", input.rubricVersion());
        snapshot.put("output_schema_version", input.outputSchemaVersion());
        snapshot.put("dimensions", dimensions);
        snapshot.put("strength_codes", rankedCodes(input.dimensions(), true));
        snapshot.put("improvement_codes", rankedCodes(input.dimensions(), false));
        snapshot.put("covered_sections", normalizedCodes(
                "coveredSections", input.coveredSections(), 48));
        snapshot.put("covered_topics", normalizedCodes(
                "coveredTopics", input.coveredTopics(), 64));
        snapshot.put("risk_flags", normalizedCodes("riskFlags", input.riskFlags(), 64));
        if (input.sourceStage() == JobInterviewStage.TECHNICAL_FIRST) {
            snapshot.put("algorithm_assessment_mode", "SPOKEN_REASONING_ONLY");
        }
        Map<String, Object> immutable = immutableMap(snapshot);
        requireWithinByteBudget(immutable);
        return immutable;
    }

    Map<String, Object> combine(List<StageInput> inputs) {
        List<StageInput> stages = copyList("inputs", inputs).stream()
                .sorted(Comparator.comparingInt(input -> input.sourceStage().sequence()))
                .toList();
        if (stages.size() != 2) {
            throw new IllegalArgumentException("HR context requires exactly two technical stages");
        }
        StageInput technicalFirst = stages.get(0);
        StageInput technicalSecond = stages.get(1);
        if (technicalFirst.sourceStage() != JobInterviewStage.TECHNICAL_FIRST
                || technicalSecond.sourceStage() != JobInterviewStage.TECHNICAL_SECOND) {
            throw new IllegalArgumentException(
                    "HR context requires TECHNICAL_FIRST then TECHNICAL_SECOND");
        }
        Map<String, Object> first = create(technicalFirst);
        Map<String, Object> second = create(technicalSecond);
        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("snapshot_version", "hr-context-v1");
        combined.put("target_stage", "HR_FINAL");
        combined.put("source_stages", List.of("TECHNICAL_FIRST", "TECHNICAL_SECOND"));
        combined.put("stage_snapshots", List.of(first, second));
        combined.put("covered_sections", mergedCodes(
                technicalFirst.coveredSections(), technicalSecond.coveredSections(), 48));
        combined.put("covered_topics", mergedCodes(
                technicalFirst.coveredTopics(), technicalSecond.coveredTopics(), 64));
        combined.put("risk_flags", mergedCodes(
                technicalFirst.riskFlags(), technicalSecond.riskFlags(), 64));
        Map<String, Object> immutable = immutableMap(combined);
        requireWithinByteBudget(immutable);
        return immutable;
    }

    private static List<Map<String, Object>> normalizedDimensions(
            List<DimensionEvaluation> dimensions) {
        if (dimensions.isEmpty() || dimensions.size() > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("dimensions must contain between 1 and 8 items");
        }
        Set<String> seen = new HashSet<>();
        List<DimensionEvaluation> ordered = dimensions.stream()
                .sorted(Comparator.comparing(DimensionEvaluation::code,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (DimensionEvaluation dimension : ordered) {
            if (dimension == null) {
                throw new IllegalArgumentException("dimensions must not contain null");
            }
            requireCode("dimension code", dimension.code(), 48);
            if (!seen.add(dimension.code())) {
                throw new IllegalArgumentException("Duplicate dimension code");
            }
            requireScore("dimension score", dimension.score());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("code", dimension.code());
            value.put("score", dimension.score());
            value.put("evidence", normalizeText(
                    "dimension evidence", dimension.evidence(), MAX_EVIDENCE_CODE_POINTS));
            value.put("comment", normalizeText(
                    "dimension comment", dimension.comment(), MAX_COMMENT_CODE_POINTS));
            normalized.add(value);
        }
        return normalized;
    }

    private static List<String> rankedCodes(
            List<DimensionEvaluation> dimensions, boolean strongestFirst) {
        Comparator<DimensionEvaluation> comparator =
                Comparator.comparingInt(DimensionEvaluation::score);
        if (strongestFirst) {
            comparator = comparator.reversed();
        }
        return dimensions.stream()
                .sorted(comparator.thenComparing(DimensionEvaluation::code))
                .limit(Math.min(3, dimensions.size()))
                .map(DimensionEvaluation::code)
                .toList();
    }

    private static List<String> mergedCodes(
            List<String> first, List<String> second, int maxLength) {
        List<String> values = new ArrayList<>(first.size() + second.size());
        values.addAll(first);
        values.addAll(second);
        return normalizedCodes("combinedCodes", values, maxLength);
    }

    private static List<String> normalizedCodes(
            String name, List<String> values, int maxLength) {
        if (values.size() > MAX_CODES * 2) {
            throw new IllegalArgumentException(name + " contains too many source values");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            requireCode(name, value, maxLength);
            normalized.add(value);
        }
        if (normalized.size() > MAX_CODES) {
            throw new IllegalArgumentException(name + " must contain at most 16 codes");
        }
        return List.copyOf(normalized);
    }

    private static void requireCode(String name, String value, int maxLength) {
        if (value == null || value.length() > maxLength
                || !CODE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " code is invalid");
        }
    }

    private static void requireScore(String name, int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }

    private static String normalizeText(String name, String value, int maxCodePoints) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        StringBuilder cleaned = new StringBuilder(value.length());
        boolean previousSpace = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean space = Character.isWhitespace(codePoint) || Character.isISOControl(codePoint);
            if (space) {
                if (!previousSpace && !cleaned.isEmpty()) {
                    cleaned.append(' ');
                }
                previousSpace = true;
            } else {
                cleaned.appendCodePoint(codePoint);
                previousSpace = false;
            }
        }
        String normalized = cleaned.toString().strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must contain visible text");
        }
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= maxCodePoints) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, maxCodePoints);
        return normalized.substring(0, end).stripTrailing();
    }

    private static String requireVersion(String name, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.strip();
    }

    private static <T> List<T> copyList(String name, Collection<T> values) {
        if (values == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            if (value == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static void requireWithinByteBudget(Map<String, Object> snapshot) {
        try {
            if (JSON.writeValueAsBytes(snapshot).length >= SNAPSHOT_BYTE_BUDGET) {
                throw new IllegalArgumentException(
                        "Stage context exceeds the safe JSON byte budget");
            }
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to measure stage context JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return (Map<String, Object>) freeze(source);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key.toString(), freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            return Collections.unmodifiableList(collection.stream()
                    .map(StageContextSnapshotFactory::freeze)
                    .toList());
        }
        return value;
    }

    record StageInput(
            JobInterviewStage sourceStage,
            int stageScore,
            String rubricVersion,
            String outputSchemaVersion,
            List<DimensionEvaluation> dimensions,
            List<String> coveredSections,
            List<String> coveredTopics,
            List<String> riskFlags) {
        StageInput {
            Objects.requireNonNull(sourceStage, "sourceStage");
            requireScore("stage score", stageScore);
            rubricVersion = requireVersion("rubricVersion", rubricVersion, 80);
            outputSchemaVersion = requireVersion(
                    "outputSchemaVersion", outputSchemaVersion, 40);
            dimensions = copyList("dimensions", dimensions);
            coveredSections = copyList("coveredSections", coveredSections);
            coveredTopics = copyList("coveredTopics", coveredTopics);
            riskFlags = copyList("riskFlags", riskFlags);
        }
    }
}
