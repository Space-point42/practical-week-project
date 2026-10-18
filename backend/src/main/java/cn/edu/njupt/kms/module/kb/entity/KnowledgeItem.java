package cn.edu.njupt.kms.module.kb.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识条目 —— 知识库的核心资产。
 *
 * <p>一条知识条目可以是一篇技术报告、一份课程文档、一篇论文、一段视频等，
 * 由 {@link #assetType} 区分；正文以 Markdown 存储，
 * 抽取出的纯文本 {@link #plainText} 供全文检索与 RAG 使用。</p>
 */
@Data
@Entity
@Table(name = "kb_item")
public class KnowledgeItem {

    /** 生命周期状态常量。 */
    public static final String DRAFT = "DRAFT";
    public static final String PENDING = "PENDING";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 1024)
    private String summary;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 去 Markdown 标记、拼接附件正文后的纯文本，检索与向量化均基于此字段。 */
    @Lob
    @Column(name = "plain_text", columnDefinition = "LONGTEXT")
    private String plainText;

    @Column(name = "content_format", nullable = false, length = 16)
    private String contentFormat = "MARKDOWN";

    /** DOCUMENT / REPORT / PAPER / VIDEO / IMAGE / CODE / MINDMAP / OTHER */
    @Column(name = "asset_type", nullable = false, length = 32)
    private String assetType = "DOCUMENT";

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "author_id")
    private Long authorId;

    /** DRAFT / PENDING / PUBLISHED / ARCHIVED */
    @Column(nullable = false, length = 16)
    private String status = DRAFT;

    @Column(name = "source_url", length = 512)
    private String sourceUrl;

    @Column(name = "source_desc", length = 255)
    private String sourceDesc;

    @Column(name = "cover_image", length = 512)
    private String coverImage;

    /** 自动抽取的关键词，英文逗号分隔。 */
    @Column(length = 512)
    private String keywords;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo = 1;

    @Column(name = "view_count", nullable = false)
    private Integer viewCount = 0;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @Column(name = "download_count", nullable = false)
    private Integer downloadCount = 0;

    /** 自上一个版本快照以来的累计修改次数，版本策略的判定依据之一。 */
    @Column(name = "change_count", nullable = false)
    private Integer changeCount = 0;

    /** 上一次生成版本快照的时间，版本策略的另一个判定依据。 */
    @Column(name = "last_snapshot_at")
    private LocalDateTime lastSnapshotAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(name = "archive_reason", length = 255)
    private String archiveReason;

    @Column(nullable = false)
    private Integer deleted = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 是否为公开可见状态。 */
    public boolean isVisible() {
        return PUBLISHED.equals(status);
    }
}
