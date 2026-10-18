package cn.edu.njupt.kms.module.rag.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 检索分块。
 *
 * <p>知识条目的正文与附件正文会被切成若干块分别向量化，
 * 检索时以「块」为最小召回单位，再回溯到所属条目给出引用。</p>
 *
 * <p>向量以 float32 小端序列存于 {@code MEDIUMBLOB}：
 * 相比 JSON 文本体积小约 4 倍，且读写无需解析。
 * 检索时按余弦相似度做暴力比对 —— 在课程设计的数据规模（万级分块）下
 * 一次全量比对耗时在几十毫秒量级，无需引入专用向量数据库。</p>
 */
@Data
@Entity
@Table(name = "rag_chunk")
public class RagChunk {

    public static final String SOURCE_CONTENT = "CONTENT";
    public static final String SOURCE_ATTACHMENT = "ATTACHMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "item_title", length = 255)
    private String itemTitle;

    @Column(name = "source_type", nullable = false, length = 16)
    private String sourceType = SOURCE_CONTENT;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex = 0;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "char_count", nullable = false)
    private Integer charCount = 0;

    /** 向量数据：float32 小端序列。 */
    @Lob
    @Column(columnDefinition = "MEDIUMBLOB")
    private byte[] embedding;

    @Column(name = "dim")
    private Integer dim;

    @Column(name = "embed_model", length = 64)
    private String embedModel;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
