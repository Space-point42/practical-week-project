package cn.edu.njupt.kms.module.ontology.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本体概念（知识点）。
 *
 * <p>概念之间通过 {@link Relation} 建立逻辑结构；同时用 {@code parentId} 维护一棵
 * 层次树（taxonomy）。两者关系：{@code parentId} 表达分类归属，
 * {@code Relation(SUB_CLASS_OF)} 表达严格的上下位（is-a）语义，
 * 层次树可视为本体的主干骨架，便于可视化与导航。</p>
 */
@Data
@Entity
@Table(name = "ont_concept")
public class Concept {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 概念唯一编码，约定 {@code 域.子域.概念} 形式，如 {@code SE.REQ.ELICITATION}。 */
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "en_name", length = 128)
    private String enName;

    /** 别名，英文逗号分隔，用于文本→概念的实体链接。 */
    @Column(length = 512)
    private String aliases;

    @Column(length = 1024)
    private String description;

    @Column(name = "parent_id")
    private Long parentId;

    /** 层次深度，根节点为 1。 */
    @Column(nullable = false)
    private Integer level = 1;

    /** 物化路径，形如 {@code /1/5/12/}，便于一次查询取整棵子树。 */
    @Column(length = 512)
    private String path;

    @Column(length = 64)
    private String domain;

    /** 认知难度 1~5。 */
    @Column(nullable = false)
    private Integer difficulty = 3;

    /** 重要程度 1~5。 */
    @Column(nullable = false)
    private Integer importance = 3;

    @Column(length = 16)
    private String color;

    /** 1=系统内置，不可删除。 */
    @Column(nullable = false)
    private Integer builtin = 0;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isRoot() {
        return parentId == null;
    }
}
