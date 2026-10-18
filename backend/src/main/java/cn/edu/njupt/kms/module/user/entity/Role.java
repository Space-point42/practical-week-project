package cn.edu.njupt.kms.module.user.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 角色。
 *
 * <p>系统采用三角色模型：</p>
 * <ul>
 *   <li>{@code ADMIN} 管理员 —— 全部权限（用户管理、本体维护、策略配置、归档）</li>
 *   <li>{@code EDITOR} 编辑 —— 知识条目与内容的创建、编辑、提交发布、版本回滚</li>
 *   <li>{@code VIEWER} 浏览者 —— 检索、浏览、下载、提问</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "sys_role")
public class Role {

    public static final String ADMIN = "ADMIN";
    public static final String EDITOR = "EDITOR";
    public static final String VIEWER = "VIEWER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
