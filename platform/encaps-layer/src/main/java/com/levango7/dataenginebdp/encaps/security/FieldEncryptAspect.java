package com.levango7.dataenginebdp.encaps.security;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;


/**
 * 字段级加解密 AOP 切面。
 *
 * <p>拦截标注 {@link Encrypt} 或 {@link Decrypt} 的 Service 方法，对其返回值
 * （单对象 / {@code List} / {@code Map}）反射扫描字段，自动完成 SM4 加密或解密。</p>
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>方法标注 {@code @Encrypt}：先 proceed 业务方法，再对返回对象中
 *       所有 {@code @Encrypt} 字段执行加密（SM4 → Base64 字符串回填）</li>
 *   <li>方法标注 {@code @Decrypt}：先 proceed 业务方法，再对返回对象中
 *       所有 {@code @Encrypt} 字段执行解密；SM3 摘要字段不可逆，跳过</li>
 *   <li>同时标注两者：按 {@code @Encrypt} 处理（写库场景）</li>
 * </ol>
 *
 * <h3>密钥选择</h3>
 * <ul>
 *   <li>方法注解 {@code key} 非空 → 使用方法注解密钥</li>
 *   <li>字段注解 {@code key} 非空 → 使用字段注解密钥</li>
 *   <li>均为空 → 使用全局密钥 {@code app.security.encrypt-key}</li>
 * </ul>
 *
 * <h3>字段类型支持</h3>
 * <p>仅处理 {@code String} 类型字段（密文以 Base64 字符串存储）；
 * 其他类型字段忽略并打 WARN 日志。</p>
 *
 * <h3>线程安全</h3>
 * <p>无共享可变状态，线程安全。反射缓存由 JVM 内部保证。</p>
 */
@Aspect
@Component
public class FieldEncryptAspect {

    private static final Logger log = LoggerFactory.getLogger(FieldEncryptAspect.class);

    /** Base64 编解码器（密文以 Base64 字符串存储于 String 字段） */
    private static final Base64.Decoder B64_DEC = Base64.getDecoder();
    private static final Base64.Encoder B64_ENC = Base64.getEncoder().withoutPadding();

    /** 全局加密密钥（hex 字符串，32 字符 = 16 字节），来自 application.yml */
    private final byte[] globalKey;

    /**
     * 构造切面。
     *
     * @param encryptKey 全局 SM4 密钥（hex 字符串），来自 {@code app.security.encrypt-key}
     */
    public FieldEncryptAspect(@Value("${app.security.encrypt-key:}") String encryptKey) {
        this.globalKey = resolveGlobalKey(encryptKey);
        log.info("FieldEncryptAspect initialized, globalKey {}",
                globalKey == null ? "disabled (no app.security.encrypt-key configured)" : "loaded");
    }

    /**
     * 拦截 {@code @Encrypt} 标注的方法，对返回值字段自动加密。
     *
     * @param pjp   连接点
     * @param encrypt 注解实例
     * @return 业务方法原始返回值（字段已被加密）
     * @throws Throwable 业务方法抛出的异常透传
     */
    @Around("@annotation(encrypt)")
    public Object aroundEncrypt(ProceedingJoinPoint pjp, Encrypt encrypt) throws Throwable {
        Object result = pjp.proceed();
        if (result == null) {
            return null;
        }
        byte[] methodKey = resolveKey(encrypt.key());
        try {
            processObject(result, methodKey, true);
        } catch (CryptoException e) {
            log.warn("Field encrypt failed for method {}: {}",
                    ((MethodSignature) pjp.getSignature()).toShortString(), e.getMessage());
        }
        return result;
    }

    /**
     * 拦截 {@code @Decrypt} 标注的方法，对返回值字段自动解密。
     *
     * @param pjp   连接点
     * @param decrypt 注解实例
     * @return 业务方法原始返回值（字段已被解密）
     * @throws Throwable 业务方法抛出的异常透传
     */
    @Around("@annotation(decrypt)")
    public Object aroundDecrypt(ProceedingJoinPoint pjp, Decrypt decrypt) throws Throwable {
        Object result = pjp.proceed();
        if (result == null) {
            return null;
        }
        byte[] methodKey = resolveKey(decrypt.key());
        try {
            processObject(result, methodKey, false);
        } catch (CryptoException e) {
            log.warn("Field decrypt failed for method {}: {}",
                    ((MethodSignature) pjp.getSignature()).toShortString(), e.getMessage());
        }
        return result;
    }

    // ===== 内部处理 =====

    /**
     * 递归处理对象：支持单对象、Collection、Map、数组。
     *
     * @param target    待处理对象
     * @param methodKey 方法级密钥（可空）
     * @param encrypt   true=加密，false=解密
     */
    private void processObject(Object target, byte[] methodKey, boolean encrypt) {
        if (target == null) {
            return;
        }
        // 集合：递归处理每个元素
        if (target instanceof Collection<?> coll) {
            for (Object item : coll) {
                processObject(item, methodKey, encrypt);
            }
            return;
        }
        // Map：递归处理 value（key 不加密）
        if (target instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                processObject(value, methodKey, encrypt);
            }
            return;
        }
        // 数组：递归处理每个元素
        if (target.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(target);
            for (int i = 0; i < len; i++) {
                processObject(java.lang.reflect.Array.get(target, i), methodKey, encrypt);
            }
            return;
        }
        // 普通对象：扫描字段
        processFields(target, methodKey, encrypt);
    }

    /**
     * 扫描对象中所有 {@code @Encrypt} 标注的 String 字段并加/解密。
     *
     * @param target    目标对象
     * @param methodKey 方法级密钥（可空）
     * @param encrypt   true=加密，false=解密
     */
    private void processFields(Object target, byte[] methodKey, boolean encrypt) {
        Class<?> clazz = target.getClass();
        // 跳过 JDK 类型与基本类型，避免误扫描
        if (clazz.getName().startsWith("java.")) {
            return;
        }
        for (Field field : clazz.getDeclaredFields()) {
            Encrypt ann = field.getAnnotation(Encrypt.class);
            if (ann == null) {
                continue;
            }
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            if (field.getType() != String.class) {
                log.warn("@Encrypt on non-String field {}.{}, skipped",
                        clazz.getSimpleName(), field.getName());
                continue;
            }
            try {
                field.setAccessible(true);
                String value = (String) field.get(target);
                if (value == null || value.isEmpty()) {
                    continue;
                }
                String processed = processField(value, ann, methodKey, encrypt);
                field.set(target, processed);
            } catch (IllegalAccessException e) {
                log.warn("Cannot access field {}.{}: {}",
                        clazz.getSimpleName(), field.getName(), e.getMessage());
            } catch (CryptoException e) {
                log.warn("Field {}.{} {} failed: {}",
                        clazz.getSimpleName(), field.getName(),
                        encrypt ? "encrypt" : "decrypt", e.getMessage());
            }
        }
    }

    /**
     * 处理单个字段值。
     *
     * @param value     原始值
     * @param ann       字段上的 {@code @Encrypt} 注解
     * @param methodKey 方法级密钥（可空）
     * @param encrypt   true=加密，false=解密
     * @return 处理后的值
     */
    private String processField(String value, Encrypt ann, byte[] methodKey, boolean encrypt) {
        // 密钥优先级：方法注解 > 字段注解 > 全局
        byte[] key = methodKey;
        if (key == null) {
            key = resolveKey(ann.key());
        }
        if (key == null) {
            throw new CryptoException("No SM4 key available for field encryption"
                    + " (configure app.security.encrypt-key or specify key() in annotation)");
        }

        if (ann.value() == EncryptType.SM3) {
            // SM3 不可逆：仅加密时计算摘要，解密时跳过
            if (encrypt) {
                byte[] hash = SmCryptoUtil.sm3Hash(value);
                return B64_ENC.encodeToString(hash);
            }
            // 解密 SM3 字段：保留原值（无法还原）
            return value;
        }

        // SM4 对称加解密
        if (encrypt) {
            byte[] cipher = SmCryptoUtil.sm4Encrypt(
                    value.getBytes(StandardCharsets.UTF_8), key);
            return B64_ENC.encodeToString(cipher);
        }
        byte[] plain = SmCryptoUtil.sm4Decrypt(B64_DEC.decode(value), key);
        return new String(plain, StandardCharsets.UTF_8);
    }

    // ===== 密钥解析 =====

    /**
     * 解析 hex 密钥字符串为 16 字节 SM4 密钥。
     *
     * @param hexKey hex 字符串（32 字符）；空或 null 返回 null
     * @return 16 字节密钥；输入为空返回 null
     */
    private static byte[] resolveKey(String hexKey) {
        if (hexKey == null || hexKey.isBlank()) {
            return null;
        }
        return SmCryptoUtil.sm4KeyFromHex(hexKey.trim());
    }

    /**
     * 解析全局密钥配置。
     *
     * @param encryptKey 来自 yml 的 hex 字符串
     * @return 16 字节密钥；配置为空返回 null（切面将在使用时抛 CryptoException）
     */
    private static byte[] resolveGlobalKey(String encryptKey) {
        if (encryptKey == null || encryptKey.isBlank()) {
            return null;
        }
        try {
            return SmCryptoUtil.sm4KeyFromHex(encryptKey.trim());
        } catch (CryptoException e) {
            // 配置非法时不阻断启动，仅记录；运行时使用该密钥会再次抛错
            LoggerFactory.getLogger(FieldEncryptAspect.class)
                    .error("Invalid app.security.encrypt-key (must be 32 hex chars): {}", e.getMessage());
            return null;
        }
    }
}