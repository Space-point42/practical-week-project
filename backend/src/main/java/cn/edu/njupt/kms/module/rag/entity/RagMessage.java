package cn.edu.njupt.kms.module.rag.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** RAG 问答消息：一次提问或一次回答。 */
@Data
@Entity
@Table(name = "rag_message")
public class RagMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 16)
    private String role;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 引用来源的 JSON 数组。 */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String citations;

    /** 检索过程元数据的 JSON（命中分块、各路得分、耗时）。 */
    @Lob
    @Column(name = "retrieval_meta", columnDefinition = "TEXT")
    private String retrievalMeta;

    /** 生成该回答的模型标识；离线抽取式回答记为 extractive。 */
    @Column(length = 64)
    private String model;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
