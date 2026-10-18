package cn.edu.njupt.kms.module.search.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 检索行为日志：用于热搜词统计与检索质量分析。 */
@Data
@Entity
@Table(name = "search_log")
public class SearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 255)
    private String keyword;

    /** SIMPLE 简单检索 / ADVANCED 高级检索 / RAG 问答式检索 */
    @Column(name = "search_type", nullable = false, length = 16)
    private String searchType = "SIMPLE";

    /** 检索条件的 JSON 快照 */
    @Column(length = 1024)
    private String filters;

    @Column(name = "result_count", nullable = false)
    private Integer resultCount = 0;

    @Column(name = "cost_ms", nullable = false)
    private Integer costMs = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
