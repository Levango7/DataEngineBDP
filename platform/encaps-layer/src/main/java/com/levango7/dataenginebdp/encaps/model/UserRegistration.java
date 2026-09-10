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
 * 用户注册审批（UserRegistration）持久化模型。
 *
 * <p>员工通过邀请码注册后进入 PENDING 状态，
 * 租户管理员在 /admin/approvals 页面审批后转为 ACTIVE。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "user_registrations",
    indexes = {
        @Index(name = "idx_reg_username", columnList = "username", unique = true),
        @Index(name = "idx_reg_tenant", columnList = "tenantId")
    }
)
public class UserRegistration {

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 注册用户名（租户内唯一） */
    private String username;

    /** 邮箱 */
    private String email;

    /** 姓名（中文） */
    private String fullName;

    /** 部门 */
    private String department;

    /** 工号/编号 */
    private String employeeId;

    /** 角色：TENANT_ADMIN / USER */
    private String role;

    /** 绑定租户 */
    private Long tenantId;

    /** 使用的邀请码 */
    private String inviteCode;

    /** 状态：PENDING / APPROVED / REJECTED */
    private String status;

    /** 审批人 */
    private String approvedBy;

    /** 审批备注 */
    private String approveNote;

    /** 申请时间 */
    private LocalDateTime createdAt;

    /** 审批时间 */
    private LocalDateTime approvedAt;
}
