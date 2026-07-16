package icu.sakuracianna.mianba.speech;

import icu.sakuracianna.mianba.platform.config.SpeechProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 为腾讯云 JSON API 生成 TC3-HMAC-SHA256 Authorization 请求头。 */
public final class TencentCloudV3Signer {
    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private final SpeechProperties properties;
    private final Clock clock;

    public TencentCloudV3Signer(SpeechProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 创建签名头；请求体必须与随后发送的 UTF-8 字节完全一致。 */
    public SignedHeaders sign(String host, String service, String payload) {
        if (!properties.configured()) {
            throw new IllegalStateException("Tencent speech credentials are incomplete");
        }
        long timestamp = clock.instant().getEpochSecond();
        String date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(clock.instant());
        String signedHeaders = "content-type;host";
        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n" + "host:" + host + "\n";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + sha256(payload);
        String scope = date + '/' + service + "/tc3_request";
        String stringToSign = ALGORITHM + '\n' + timestamp + '\n' + scope + '\n' + sha256(canonicalRequest);

        byte[] secretDate = hmac(("TC3" + properties.tencentSecretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, service);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));
        String authorization = ALGORITHM + " Credential=" + properties.tencentSecretId() + '/' + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return new SignedHeaders(authorization, Long.toString(timestamp), CONTENT_TYPE);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hmac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Unable to create Tencent API signature", exception);
        }
    }

    /** 已签名公共请求头，不包含任何原始 SecretKey。 */
    public record SignedHeaders(String authorization, String timestamp, String contentType) {
    }
}
