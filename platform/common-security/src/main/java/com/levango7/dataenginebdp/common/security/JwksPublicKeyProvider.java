package com.levango7.dataenginebdp.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keycloak JWKS 公钥提供器（JWT 全链路"最后一公里"第一段）。
 *
 * <p><b>背景</b>：平台原有鉴权只验自签 HS256 token（{@code app.security.jwt.secret}），
 * 而 Keycloak 签发的是 RS256 token——网关的 {@code keycloak-auth} 插件只有一份 YAML
 * 说明、没有实现，服务层也完全不认 RS256。结果是"Keycloak  realm 已部署但签发的 token
 * 到服务层就是 401"，鉴权最后一公里断开。</p>
 *
 * <p>本类按 {@code kid} 从 Keycloak 的 JWKS 端点拉取 RS256 公钥并缓存，供
 * {@link JwtAuthFilter} 与业务模块验签使用。配置 {@code app.security.jwt.jwks-uri}
 * 即启用；未配置时任何模块行为与改动前完全一致。</p>
 *
 * <h3>三条设计约束</h3>
 * <ol>
 *   <li><b>fail-fast 而非 fail-open</b>：拉取失败/解析失败一律抛异常，不返回 null，
 *       不静默放行——鉴权组件绝不能"取不到钥匙就当作验过"。</li>
 *   <li><b>TTL 缓存 + 按 kid 命中</b>：默认 10 分钟；Keycloak 轮换密钥签发新 kid 时，
 *       未命中会主动拉取一次（旧 kid 自然淘汰）。</li>
 *   <li><b>零依赖新增</b>：只用 JDK 自带 {@link java.net.http.HttpClient} 与 Jackson
 *       （common-security 已有），不引入 jjwt-jwks 之类的额外依赖。</li>
 * </ol>
 *
 * @author shuqing-bigdata
 */
public class JwksPublicKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwksPublicKeyProvider.class);

    /** RSA 公钥算法名（Keycloak RS256 用） */
    private static final String RSA_ALG = "RSA";

    private final String jwksUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration ttl;
    private final Map<String, CachedKey> cache = new ConcurrentHashMap<>();

    /**
     * @param jwksUri    JWKS 端点（如 {@code https://kc/realms/shuqing/protocol/openid-connect/certs}）
     * @param connectTimeout 连接超时；null 用默认 3s
     * @param readTimeout    读取超时；null 用默认 5s
     * @param ttl            缓存时长；null 用默认 10 分钟
     */
    public JwksPublicKeyProvider(String jwksUri,
                                 Duration connectTimeout,
                                 Duration readTimeout,
                                 Duration ttl) {
        if (jwksUri == null || jwksUri.isBlank()) {
            throw new IllegalArgumentException("jwksUri 不能为空");
        }
        this.jwksUri = jwksUri;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout)
                .build();
        this.ttl = ttl == null ? Duration.ofMinutes(10) : ttl;
    }

    /**
     * 按 {@code kid} 取 RS256 公钥。
     *
     * @param kid JWT header 里的 key id
     * @return RSA 公钥
     * @throws JwtKeyException 拉取失败、kid 不存在或密钥格式非法
     */
    public PublicKey resolve(String kid) {
        if (kid == null || kid.isBlank()) {
            // 无 kid 的 token 无法在多密钥间定位：按 fail-fast 处理
            throw new JwtKeyException("RS256 token 缺少 kid header，无法定位 JWKS 公钥");
        }
        CachedKey cached = cache.get(kid);
        if (cached != null && !cached.isExpired(Instant.now())) {
            return cached.key;
        }
        return refresh(kid);
    }

    /** 强制刷新指定 kid（缓存穿透/主动失效场景）。 */
    public PublicKey refresh(String kid) {
        JsonNode jwks = fetchJwks();
        JsonNode keys = jwks.get("keys");
        if (keys == null || !keys.isArray()) {
            throw new JwtKeyException("JWKS 响应缺少 keys 数组: " + jwksUri);
        }
        PublicKey matched = null;
        for (Iterator<JsonNode> it = keys.elements(); it.hasNext(); ) {
            JsonNode k = it.next();
            if (!RSA_ALG.equals(k.path("kty").asText())) {
                continue;
            }
            String keyId = k.path("kid").asText(null);
            PublicKey parsed = toPublicKey(k);
            cache.put(keyId, new CachedKey(parsed, Instant.now().plus(ttl)));
            if (kid.equals(keyId)) {
                matched = parsed;
            }
        }
        if (matched == null) {
            throw new JwtKeyException("JWKS 中找不到 kid=" + kid + " 的公钥: " + jwksUri);
        }
        log.info("JWKS 公钥已刷新 kid={} (uri={})", kid, jwksUri);
        return matched;
    }

    /** 拉取 JWKS 文档（fail-fast：非 2xx 一律异常）。 */
    private JsonNode fetchJwks() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new JwtKeyException("JWKS 端点返回 " + response.statusCode() + ": " + jwksUri);
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new JwtKeyException("拉取 JWKS 失败: " + jwksUri + " (" + e.getMessage() + ")", e);
        }
    }

    /** JWK(RSA) → {@link PublicKey}。 */
    private PublicKey toPublicKey(JsonNode jwk) {
        String n = jwk.path("n").asText(null);
        String e = jwk.path("e").asText(null);
        if (n == null || e == null) {
            throw new JwtKeyException("RSA JWK 缺少 n/e: " + jwk);
        }
        byte[] nBytes = Base64.getUrlDecoder().decode(n);
        byte[] eBytes = Base64.getUrlDecoder().decode(e);
        // javax.crypto.SecretKey 未用到：这里用标准 RSA X509 编码拼 PublicKey，
        // 依赖 jjwt 之外的标准 JCA 接口（Keycloak 的 RSA 公钥即 X.509 SubjectPublicKeyInfo）。
        byte[] spki = spkiWrap(nBytes, eBytes);
        try {
            return KeyFactory.getInstance(RSA_ALG).generatePublic(new X509EncodedKeySpec(spki));
        } catch (Exception ex) {
            throw new JwtKeyException("构造 RSA 公钥失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 拼 X.509 SubjectPublicKeyInfo DER（RSA 公钥的标准封装）。
     *
     * <p>结构：{@code SEQUENCE { SEQUENCE { OID rsaEncryption, NULL }, BIT STRING { RSAPublicKey } }}，
     * 其中 {@code RSAPublicKey = SEQUENCE { INTEGER modulus, INTEGER exponent }}。
     * 用标准 JCA {@code KeyFactory} 生成公钥，避免依赖内部 API。</p>
     */
    private byte[] spkiWrap(byte[] nBytes, byte[] eBytes) {
        byte[] rsaPublicKey = derSequence(derInteger(nBytes), derInteger(eBytes));
        byte[] algorithmId = derSequence(
                derTlv((byte) 0x06, new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01}),  // OID rsaEncryption
                new byte[]{0x05, 0x00}  // NULL
        );
        byte[] bitString = derTlv((byte) 0x03, concat(new byte[]{0x00}, rsaPublicKey));  // 前导 0 = 未用位数
        return derSequence(algorithmId, bitString);
    }

    private static byte[] derSequence(byte[]... parts) {
        return derTlv((byte) 0x30, concat(parts));
    }

    private static byte[] derInteger(byte[] value) {
        byte[] body = value;
        if (body.length == 0 || (body[0] & 0x80) != 0) {
            byte[] padded = new byte[body.length + 1];
            padded[0] = 0;
            System.arraycopy(body, 0, padded, 1, body.length);
            body = padded;
        }
        return derTlv((byte) 0x02, body);
    }

    private static byte[] derTlv(byte tag, byte[] body) {
        byte[] len = derLength(body.length);
        byte[] out = new byte[1 + len.length + body.length];
        out[0] = tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(body, 0, out, 1 + len.length, body.length);
        return out;
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        int bytes = 1;
        int v = length;
        while ((v >>>= 8) != 0) {
            bytes++;
        }
        byte[] out = new byte[bytes + 1];
        out[0] = (byte) (0x80 | bytes);
        for (int i = 0; i < bytes; i++) {
            out[bytes - i] = (byte) (length >>> (8 * i));
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /** 缓存条目。 */
    private record CachedKey(PublicKey key, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    /**
     * JWKS 拉取/解析失败（fail-fast）。
     *
     * <p>不继承 {@link io.jsonwebtoken.JwtException}：调用方（过滤器）把它归入
     * "取不到钥匙"这一类基础设施故障，与"token 本身不合法"分开处理。</p>
     */
    public static final class JwtKeyException extends RuntimeException {
        public JwtKeyException(String message) {
            super(message);
        }

        public JwtKeyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
