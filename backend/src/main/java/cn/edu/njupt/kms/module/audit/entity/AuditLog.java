package cn.edu.njupt.kms.module.audit.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 审计日志：记录关键写操作，用于追溯与答辩演示。 */
@Data
@Entity
@Table(name = "sys_audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 64)
    private String username;

    /** CREATE / UPDATE / DELETE / PUBLISH / ARCHIVE / ROLLBACK / LOGIN ... */
    @Column(nullable = false, length = 32)
    private String action;

    /** ITEM / CONCEPT / CATEGORY / USER / POLICY ... */
    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(length = 1024)
    private String detail;

    @Column(length = 64)
    private String ip;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
