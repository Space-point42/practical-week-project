package cn.edu.njupt.kms.module.ontology.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 本体关系实例（三元组 {@code (source, relationType, target)}）。
 *
 * <p>{@code description} 保存三元组的自然语言断言，
 * 例如“需求获取 是 需求工程 的组成部分”，便于在界面上直接展示与讲解。</p>
 */
@Data
@Entity
@Table(name = "ont_relation")
public class Relation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "relation_type", nullable = false, length = 32)
    private String relationType;

    /** 关系强度 0~1，用于图谱布局与推理排序。 */
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal weight = BigDecimal.ONE;

    @Column(length = 512)
    private String description;

    @Column(nullable = false)
    private Integer builtin = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
