package com.levango7.dataenginebdp.encaps.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户邀请码（InviteCode）持久化模型。
 *
 * <p>由平台超管或租户管理员生成，绑定到指定租户，
 * 员工通过该码在 /register 页面激活账号并进入审批流程。</p>
 *
 * <p>生命周期：PENDING（待激活）→ ACTIVE（已激活）→ EXPIRED（过期自动）
 * / CANCELLED（管理员撤销）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "invite_codes",
    indexes = {
        @Index(name = "idx_invite_code", columnList = "code", unique = true),
        @Index(name = "idx_invite_tenant", columnList = "tenantId")
    }
)
public class InviteCode {

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 邀请码（8 位大写字母+数字，租户内唯一） */
    private String code;

    /** 绑定租户 ID（来自 tenants.id） */
    private Long tenantId;

    /** 绑定的角色：PLATFORM_ADMIN / TENANT_ADMIN / USER */
    private String role;

    /** 邀请人用户名（生成该码的管理员） */
    private String invitedBy;

    /** 邀请备注/职位建议 */
    private String note;

    /** 状态：PENDING / ACTIVE / EXPIRED / CANCELLED */
    private String status;

    /** 激活时间（被员工使用后） */
    private LocalDateTime activatedAt;

    /** 激活人用户名（员工激活后填入） */
    private String activatedBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 过期时间（默认 7 天） */
    private LocalDateTime expiresAt;
}
