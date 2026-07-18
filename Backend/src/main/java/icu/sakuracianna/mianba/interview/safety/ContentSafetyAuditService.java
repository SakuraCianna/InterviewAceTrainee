package icu.sakuracianna.mianba.interview.safety;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 以独立事务记录不含原文的输入输出风控元数据。 */
@Service
public class ContentSafetyAuditService {
    private static final byte[] DIGEST_DOMAIN =
            "mianba:content-safety-audit:v1:".getBytes(StandardCharsets.UTF_8);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final byte[] keyMaterial;

    public ContentSafetyAuditService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ContentSafetyProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.keyMaterial = properties.auditHmacSecret().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInput(
            UUID userId,
            UUID sessionId,
            String requestId,
            String source,
            String content,
            AnswerSafetyPolicy.Finding finding) {
        insert(userId, sessionId, requestId, null, source, content,
                finding.riskLevel(), finding.categories(), finding.matchedRuleIds(),
                finding.blocked(), finding.messageCode());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutput(
            UUID sessionId,
            UUID jobId,
            String source,
            String content,
            AiOutputSafetyPolicy.Finding finding) {
        insert(null, sessionId, null, jobId, source, content,
                finding.riskLevel(), finding.categories(), finding.ruleIds(),
                finding.blocked(), finding.messageCode());
    }

    private void insert(
            UUID userId,
            UUID sessionId,
            String requestId,
            UUID jobId,
            String source,
            String content,
            String riskLevel,
            List<String> categories,
            List<String> ruleIds,
            boolean blocked,
            String messageCode) {
        try {
            String disposition = blocked ? "blocked" : "replaced";
            jdbc.update("""
                    INSERT INTO content_safety(
                        user_id, session_id, request_id, job_id, source, action, risk_level,
                        categories, rule_ids, message_code, content_digest, disposition)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                    """,
                    userId, sessionId, requestId, jobId, source, disposition, riskLevel,
                    mapper.writeValueAsString(categories), mapper.writeValueAsString(ruleIds),
                    messageCode, digest(content), disposition);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize content safety metadata", exception);
        }
    }

    private String digest(String content) {
        byte[] normalized = AnswerSafetyPolicy.normalize(content == null ? "" : content)
                .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] result;
            if (keyMaterial.length == 0) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(DIGEST_DOMAIN);
                result = digest.digest(normalized);
            } else {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(keyMaterial, "HmacSHA256"));
                mac.update(DIGEST_DOMAIN);
                result = mac.doFinal(normalized);
            }
            return HexFormat.of().formatHex(result);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Content safety digest is unavailable", exception);
        }
    }
}
