package cn.edu.njupt.kms.module.cms.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识条目的历史版本快照。
 *
 * <p>每次生成快照都会完整保存该时刻的标题、摘要、正文与标注信息，
 * 因此回滚操作只需把快照内容写回条目，无需重放历史变更。</p>
 */
@Data
@Entity
@Table(name = "cms_item_version")
public class ItemVersion {

    /** 快照来源：自动触发。 */
    public static final String SOURCE_AUTO = "AUTO";
    /** 快照来源：用户手工创建。 */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 快照来源：发布动作触发。 */
    public static final String SOURCE_PUBLISH = "PUBLISH";
    /** 快照来源：回滚动作生成（记录回滚后的状态）。 */
    public static final String SOURCE_ROLLBACK = "ROLLBACK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    /** 版本序号，从 1 开始递增。 */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 1024)
    private String summary;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(name = "asset_type", length = 32)
    private String assetType;

    /** 概念标注快照，形如 {@code 12:软件测试|15:单元测试}。 */
    @Column(name = "concepts_snapshot", length = 1024)
    private String conceptsSnapshot;

    /** 标签快照，逗号分隔。 */
    @Column(name = "tags_snapshot", length = 1024)
    private String tagsSnapshot;

    /** 版本说明。 */
    @Column(name = "change_log", length = 1024)
    private String changeLog;

    /** AUTO / MANUAL / PUBLISH / ROLLBACK */
    @Column(name = "change_source", nullable = false, length = 16)
    private String changeSource = SOURCE_AUTO;

    /** 相对上一版本新增字符数。 */
    @Column(name = "char_added", nullable = false)
    private Integer charAdded = 0;

    /** 相对上一版本删除字符数。 */
    @Column(name = "char_removed", nullable = false)
    private Integer charRemoved = 0;

    /** 正文 MD5，用于判断内容是否真的发生了变化。 */
    @Column(name = "content_hash", length = 32)
    private String contentHash;

    @Column(name = "editor_id")
    private Long editorId;

    @Column(name = "editor_name", length = 64)
    private String editorName;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
