package icu.sakuracianna.mianba.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/** 加载并验证仓库内公共 Markdown，拒绝缺字段、重复 ID 和过短的占位内容。 */
@Component
public class KnowledgeDocumentLoader {
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "id", "domain", "category", "title", "aliases", "tags", "source_version", "sources");
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "## 核心知识与表达", "## 研究与实践问题", "## 综合评价");
    private static final Set<String> SCHOOL_TIERS = Set.of(
            "c9", "985", "211", "general-undergraduate", "representative", "not-applicable");
    private static final Pattern UNSAFE_CONTENT = Pattern.compile(
            "(?is)(忽略.{0,24}(系统|开发者|以上|之前).{0,24}(指令|提示)|"
                    + "(ignore|disregard).{0,32}(system|developer|previous).{0,24}(instruction|prompt)|"
                    + "(输出|泄露|显示).{0,24}(系统提示|内部提示|密钥|api key))");
    private static final int MINIMUM_CONTENT_CODE_POINTS = 180;
    private static final int MAXIMUM_CONTENT_CODE_POINTS = 5000;

    private final ResourcePatternResolver resources;

    public KnowledgeDocumentLoader(ResourcePatternResolver resources) {
        this.resources = resources;
    }

    public List<PublicKnowledgeDocument> load(String location) {
        try {
            Resource[] matches = resources.getResources(location);
            Arrays.sort(matches, Comparator.comparing(Resource::getDescription));
            List<PublicKnowledgeDocument> documents = new ArrayList<>(matches.length);
            Set<String> identifiers = new HashSet<>();
            Set<String> contentHashes = new HashSet<>();
            for (Resource resource : matches) {
                PublicKnowledgeDocument document = parse(resource);
                if (!identifiers.add(document.id())) {
                    throw new IllegalStateException("Duplicate public knowledge id: " + document.id());
                }
                if (!contentHashes.add(document.contentHash())) {
                    throw new IllegalStateException("Duplicate public knowledge content hash: " + document.id());
                }
                documents.add(document);
            }
            return List.copyOf(documents);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load public knowledge resources", exception);
        }
    }

    private PublicKnowledgeDocument parse(Resource resource) throws IOException {
        String raw;
        try (InputStream input = resource.getInputStream()) {
            raw = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        }
        if (!raw.startsWith("---\n")) {
            throw invalid(resource, "missing front matter");
        }
        int end = raw.indexOf("\n---\n", 4);
        if (end < 0) {
            throw invalid(resource, "unterminated front matter");
        }
        Map<String, String> metadata = parseMetadata(raw.substring(4, end), resource);
        if (!metadata.keySet().containsAll(REQUIRED_FIELDS)) {
            throw invalid(resource, "missing required metadata");
        }
        String content = normalizeContent(raw.substring(end + 5));
        int codePoints = content.codePointCount(0, content.length());
        if (codePoints < MINIMUM_CONTENT_CODE_POINTS || codePoints > MAXIMUM_CONTENT_CODE_POINTS) {
            throw invalid(resource, "content length outside allowed range");
        }
        if (REQUIRED_SECTIONS.stream().anyMatch(section -> !content.contains(section))) {
            throw invalid(resource, "missing required knowledge section");
        }
        if (UNSAFE_CONTENT.matcher(content).find()) {
            throw invalid(resource, "unsafe instruction in public content");
        }
        String id = safeCode(metadata.get("id"), 120, resource);
        KnowledgeDomain domain = parseDomain(metadata.get("domain"), resource);
        String category = safeCode(metadata.get("category"), 80, resource);
        String title = boundedText(metadata.get("title"), 160, resource);
        List<String> aliases = parseList(metadata.get("aliases"), 12, 120, resource, "aliases");
        List<String> tags = parseList(metadata.get("tags"), 24, 160, resource, "tags");
        String schoolTier = domain == KnowledgeDomain.POSTGRADUATE
                ? safeSchoolTier(metadata.get("school_tier"), resource)
                : "not-applicable";
        String version = safeCode(metadata.get("source_version"), 40, resource);
        List<String> sources = parseSources(metadata.get("sources"), resource);
        return new PublicKnowledgeDocument(
                id, domain, category, title, aliases, tags, schoolTier,
                version, sources, content, sha256(content));
    }

    private static Map<String, String> parseMetadata(String frontMatter, Resource resource) {
        Map<String, String> metadata = new HashMap<>();
        for (String line : frontMatter.split("\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw invalid(resource, "invalid metadata line");
            }
            String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (value.isEmpty() || metadata.putIfAbsent(key, value) != null) {
                throw invalid(resource, "empty or duplicate metadata key");
            }
        }
        return metadata;
    }

    private static KnowledgeDomain parseDomain(String raw, Resource resource) {
        return switch (raw) {
            case "job" -> KnowledgeDomain.JOB;
            case "postgraduate" -> KnowledgeDomain.POSTGRADUATE;
            default -> throw invalid(resource, "unsupported domain");
        };
    }

    private static List<String> parseSources(String raw, Resource resource) {
        List<String> sources = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        if (sources.isEmpty() || sources.size() > 8
                || sources.stream().anyMatch(source ->
                        !(source.startsWith("https://") && source.length() <= 500))) {
            throw invalid(resource, "sources must contain bounded HTTPS URLs");
        }
        return sources;
    }

    private static List<String> parseList(
            String raw,
            int maximumItems,
            int maximumItemLength,
            Resource resource,
            String field) {
        if (raw == null) {
            throw invalid(resource, "missing " + field);
        }
        List<String> values = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (values.isEmpty() || values.size() > maximumItems
                || values.stream().anyMatch(value -> value.length() > maximumItemLength
                        || value.codePoints().anyMatch(Character::isISOControl))) {
            throw invalid(resource, "invalid " + field);
        }
        return values;
    }

    private static String safeSchoolTier(String raw, Resource resource) {
        if (raw == null || !SCHOOL_TIERS.contains(raw)) {
            throw invalid(resource, "invalid school tier");
        }
        return raw;
    }

    private static String normalizeContent(String raw) {
        return raw.strip().replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
    }

    private static String safeCode(String raw, int maximum, Resource resource) {
        if (raw == null || raw.length() > maximum || !raw.matches("[a-z0-9][a-z0-9._-]*")) {
            throw invalid(resource, "invalid code metadata");
        }
        return raw;
    }

    private static String boundedText(String raw, int maximum, Resource resource) {
        if (raw == null || raw.isBlank() || raw.length() > maximum
                || raw.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(resource, "invalid title metadata");
        }
        return raw;
    }

    private static IllegalStateException invalid(Resource resource, String reason) {
        return new IllegalStateException("Invalid public knowledge resource "
                + resource.getDescription() + ": " + reason);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
