package cn.edu.njupt.kms.module.ontology.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 本体关系类型（关系的元模型）。
 *
 * <p>系统内置 7 种关系（满足题目“≥5 种关系”的要求），
 * 其中 {@code RELATED_TO} 与 {@code SYNONYM_OF} 为对称关系（{@code directed=0}），
 * 其余为有向关系，且各自定义逆关系编码便于双向推理展示。</p>
 */
@Data
@Entity
@Table(name = "ont_relation_type")
public class RelationType {

    // ---- 内置关系编码常量 ----
    /** 上下位 / 继承（is-a）：子概念 → 父概念。 */
    public static final String SUB_CLASS_OF = "SUB_CLASS_OF";
    /** 组成（part-of）：部分 → 整体。 */
    public static final String PART_OF = "PART_OF";
    /** 先修：前置知识 → 后续知识。 */
    public static final String PREREQUISITE_OF = "PREREQUISITE_OF";
    /** 相关（对称）：知识间的一般关联。 */
    public static final String RELATED_TO = "RELATED_TO";
    /** 应用于：方法/理论 → 应用场景。 */
    public static final String APPLIED_IN = "APPLIED_IN";
    /** 产出：活动/过程 → 制品。 */
    public static final String PRODUCES = "PRODUCES";
    /** 近义 / 别名（对称）。 */
    public static final String SYNONYM_OF = "SYNONYM_OF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    /** 逆关系编码；对称关系填自身。 */
    @Column(name = "inverse_code", length = 32)
    private String inverseCode;

    /** 1=有向 0=无向（对称）。 */
    @Column(nullable = false)
    private Integer directed = 1;

    @Column(length = 16)
    private String color;

    @Column(length = 255)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
