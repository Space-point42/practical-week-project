package cn.edu.njupt.kms.module.kb.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识标签（扁平、可交叉的分类维度）。 */
@Data
@Entity
@Table(name = "kb_tag")
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(length = 16)
    private String color;

    @Column(name = "use_count", nullable = false)
    private Integer useCount = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
