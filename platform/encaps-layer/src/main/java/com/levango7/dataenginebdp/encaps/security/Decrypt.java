package com.levango7.dataenginebdp.encaps.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段级解密注解。
 *
 * <p>标注于 Service 查询方法上，由 {@link FieldEncryptAspect} 切面拦截返回值，
 * 对返回对象中所有 {@code @Encrypt} 标注字段自动解密。</p>
 *
 * <h3>与 {@link Encrypt} 的关系</h3>
 * <ul>
 *   <li>{@code @Encrypt} 标注于写库方法 → 写库前自动加密敏感字段</li>
 *   <li>{@code @Decrypt} 标注于查询方法 → 查询后自动解密敏感字段</li>
 *   <li>对于 SM3 摘要字段（不可逆），切面跳过解密，仅原值返回</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * @Decrypt
 * public UserEntity findById(Long id) { ... }
 *
 * @Decrypt
 * public List<UserEntity> findAll() { ... }
 * }</pre>
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Decrypt {
    /**
     * 解密密钥（hex 字符串，32 字符 = 16 字节）。
     *
     * <p>空字符串表示使用全局密钥 {@code app.security.encrypt-key}。</p>
     *
     * @return 密钥 hex 串
     */
    String key() default "";
}