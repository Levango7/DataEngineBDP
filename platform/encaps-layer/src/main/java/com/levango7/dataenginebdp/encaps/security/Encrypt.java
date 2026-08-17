package com.levango7.dataenginebdp.encaps.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段级加密注解。
 *
 * <p>标注于实体字段或 Service 方法上，由 {@link FieldEncryptAspect} 切面统一处理：</p>
 * <ul>
 *   <li>标注于方法：拦截返回值，对返回对象中所有 {@code @Encrypt} 字段自动加密</li>
 *   <li>标注于字段：被切面反射识别并加密/解密该字段值</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 1. 实体字段标注（声明敏感字段）
 * public class UserEntity {
 *     @Encrypt(EncryptType.SM4)
 *     private String phone;
 *     @Encrypt(value = EncryptType.SM3)
 *     private String passwordHash;
 * }
 *
 * // 2. Service 方法标注（写库前自动加密）
 * @Encrypt
 * public UserEntity save(UserEntity user) { ... }
 *
 * // 3. 查询方法标注（查询后自动解密）
 * @Decrypt
 * public UserEntity findById(Long id) { ... }
 * }</pre>
 *
 * <h3>密钥选择</h3>
 * <ul>
 *   <li>{@code key} 非空：使用注解上显式指定的密钥（hex 字符串，32 字符 = 16 字节）</li>
 *   <li>{@code key} 为空：使用全局密钥 {@code app.security.encrypt-key}（application.yml）</li>
 * </ul>
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Encrypt {
    /**
     * 加密算法类型，默认 SM4 对称加密。
     *
     * @return 算法类型
     */
    EncryptType value() default EncryptType.SM4;

    /**
     * 加密密钥（hex 字符串，32 字符 = 16 字节）。
     *
     * <p>空字符串表示使用全局密钥 {@code app.security.encrypt-key}。</p>
     *
     * @return 密钥 hex 串
     */
    String key() default "";
}