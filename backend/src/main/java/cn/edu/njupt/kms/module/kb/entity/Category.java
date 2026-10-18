package cn.edu.njupt.kms.module.kb.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识分类（层次结构），用于知识库的归类与导航。 */
@Data
@Entity
@Table(name = "kb_category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false)
    private Integer level = 1;

    /** 物化路径 {@code /1/4/9/}，一次查询取整棵子树。 */
    @Column(length = 512)
    private String path;

    @Column(length = 64)
    private String icon;

    @Column(length = 512)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 冗余的条目计数，列表页直接展示，由服务层维护。 */
    @Column(name = "item_count", nullable = false)
    private Integer itemCount = 0;

    @Column(nullable = false)
    private Integer builtin = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
