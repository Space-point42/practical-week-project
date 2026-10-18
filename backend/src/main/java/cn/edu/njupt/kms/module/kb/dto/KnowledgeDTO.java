package cn.edu.njupt.kms.module.kb.dto;

import cn.edu.njupt.kms.module.kb.entity.Attachment;
import cn.edu.njupt.kms.module.kb.entity.Category;
import cn.edu.njupt.kms.module.kb.entity.KnowledgeItem;
import cn.edu.njupt.kms.module.kb.entity.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 知识库模块的请求/响应对象集合。 */
public final class KnowledgeDTO {

    private KnowledgeDTO() {
    }

    // ---------------- 知识条目 ----------------

    @Data
    public static class ItemDTO {
        private Long id;
        private String title;
        private String summary;
        private String content;
        /** 纯文本正文，仅详情页在需要时返回 */
        private String plainText;
        private String contentFormat;
        private String assetType;
        private String assetTypeName;
        private Long categoryId;
        private String categoryName;
        private String categoryPath;

        private Long authorId;
        private String authorName;

        private String status;
        private String statusName;

        private String sourceUrl;
        private String sourceDesc;
        private String coverImage;
        private List<String> keywords;

        private Integer versionNo;
        private Integer viewCount;
        private Integer likeCount;
        private Integer downloadCount;

        /** 版本管理相关 */
        private Integer changeCount;
        private LocalDateTime lastSnapshotAt;
        /** 是否满足版本策略的建版条件（前端据此提示“建议创建版本”） */
        private Boolean snapshotDue;

        private LocalDateTime publishedAt;
        private LocalDateTime archivedAt;
        private String archiveReason;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        private List<TagDTO> tags;
        private List<Long> conceptIds;
        private List<String> conceptNames;
        private List<AttachmentDTO> attachments;

        /** 检索时返回的相关性得分与摘要片段 */
        private Double score;
        private String snippet;
        private String titleHighlight;

        public static ItemDTO from(KnowledgeItem i) {
            ItemDTO d = new ItemDTO();
            d.id = i.getId();
            d.title = i.getTitle();
            d.summary = i.getSummary();
            d.contentFormat = i.getContentFormat();
            d.assetType = i.getAssetType();
            d.assetTypeName = assetTypeName(i.getAssetType());
            d.categoryId = i.getCategoryId();
            d.authorId = i.getAuthorId();
            d.status = i.getStatus();
            d.statusName = statusName(i.getStatus());
            d.sourceUrl = i.getSourceUrl();
            d.sourceDesc = i.getSourceDesc();
            d.coverImage = i.getCoverImage();
            d.keywords = splitCsv(i.getKeywords());
            d.versionNo = i.getVersionNo();
            d.viewCount = i.getViewCount();
            d.likeCount = i.getLikeCount();
            d.downloadCount = i.getDownloadCount();
            d.changeCount = i.getChangeCount();
            d.lastSnapshotAt = i.getLastSnapshotAt();
            d.publishedAt = i.getPublishedAt();
            d.archivedAt = i.getArchivedAt();
            d.archiveReason = i.getArchiveReason();
            d.createdAt = i.getCreatedAt();
            d.updatedAt = i.getUpdatedAt();
            return d;
        }

        /** 详情视图：附上正文与纯文本。 */
        public static ItemDTO detail(KnowledgeItem i) {
            ItemDTO d = from(i);
            d.content = i.getContent();
            d.plainText = i.getPlainText();
            return d;
        }

        public static List<String> splitCsv(String s) {
            if (s == null || s.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        }

        public static String assetTypeName(String type) {
            if (type == null) {
                return "未知";
            }
            return switch (type) {
                case "DOCUMENT" -> "文档";
                case "REPORT" -> "技术报告";
                case "PAPER" -> "论文";
                case "VIDEO" -> "视频";
                case "IMAGE" -> "图片";
                case "CODE" -> "代码";
                case "MINDMAP" -> "思维导图";
                default -> "其他";
            };
        }

        public static String statusName(String status) {
            if (status == null) {
                return "未知";
            }
            return switch (status) {
                case "DRAFT" -> "草稿";
                case "PENDING" -> "待审核";
                case "PUBLISHED" -> "已发布";
                case "ARCHIVED" -> "已归档";
                default -> status;
            };
        }
    }

    @Data
    public static class ItemSaveRequest {
        @NotBlank(message = "标题不能为空")
        @Size(max = 255, message = "标题过长")
        private String title;

        @Size(max = 1024, message = "摘要过长")
        private String summary;

        private String content;
        private String contentFormat = "MARKDOWN";
        private String assetType = "DOCUMENT";
        private Long categoryId;
        private String sourceUrl;
        private String sourceDesc;
        private String coverImage;

        /** 标签名称列表，不存在时自动创建 */
        private List<String> tags;
        /** 手工标注的本体概念 id 列表 */
        private List<Long> conceptIds;
        /** 是否启用自动概念标注（依据本体别名匹配正文） */
        private Boolean autoTagConcepts = true;

        /** 版本说明，写入本次变更产生的版本快照 */
        private String changeLog;

        /** 附件 id 列表（先前通过上传接口获得） */
        private List<Long> attachmentIds;
    }

    /** 条目列表查询条件。 */
    @Data
    public static class ItemQuery {
        /** 关键词（标题/摘要/正文模糊匹配） */
        private String keyword;
        /** 分类 id，含其子分类 */
        private Long categoryId;
        /** 资产类型多选 */
        private List<String> assetTypes;
        /** 标签 id 多选（任意命中） */
        private List<Long> tagIds;
        /** 本体概念 id 多选（任意命中） */
        private List<Long> conceptIds;
        private Long authorId;
        /** 状态；为空时按调用方权限决定可见范围 */
        private String status;
        private LocalDateTime createdFrom;
        private LocalDateTime createdTo;
        /** 排序字段：updatedAt / createdAt / viewCount / likeCount / publishedAt */
        private String sortBy = "updatedAt";
        private String sortDir = "desc";
        private int page = 1;
        private int size = 10;
    }

    // ---------------- 分类 ----------------

    @Data
    public static class CategoryDTO {
        private Long id;
        private String name;
        private String code;
        private Long parentId;
        private Integer level;
        private String path;
        private String icon;
        private String description;
        private Integer sortOrder;
        private Integer itemCount;
        private Integer builtin;
        private long childCount;
        /** 含子分类的条目总数 */
        private long totalItemCount;
        private List<CategoryDTO> children;

        public static CategoryDTO from(Category c) {
            CategoryDTO d = new CategoryDTO();
            d.id = c.getId();
            d.name = c.getName();
            d.code = c.getCode();
            d.parentId = c.getParentId();
            d.level = c.getLevel();
            d.path = c.getPath();
            d.icon = c.getIcon();
            d.description = c.getDescription();
            d.sortOrder = c.getSortOrder();
            d.itemCount = c.getItemCount();
            d.builtin = c.getBuiltin();
            return d;
        }
    }

    @Data
    public static class CategorySaveRequest {
        @NotBlank(message = "分类名称不能为空")
        private String name;
        private String code;
        private Long parentId;
        private String icon;
        private String description;
        private Integer sortOrder;
    }

    // ---------------- 标签 ----------------

    @Data
    public static class TagDTO {
        private Long id;
        private String name;
        private String color;
        private Integer useCount;

        public static TagDTO from(Tag t) {
            TagDTO d = new TagDTO();
            d.id = t.getId();
            d.name = t.getName();
            d.color = t.getColor();
            d.useCount = t.getUseCount();
            return d;
        }
    }

    // ---------------- 附件 ----------------

    @Data
    public static class AttachmentDTO {
        private Long id;
        private Long itemId;
        private String fileName;
        private String storedName;
        private String filePath;
        private String url;
        private String downloadUrl;
        private Long fileSize;
        private String readableSize;
        private String mimeType;
        private String mediaType;
        private String extension;
        private String extractStatus;
        /** 抽取正文的长度，界面用于提示“该文件可被检索覆盖” */
        private Integer textLength;
        private Integer durationSec;
        private LocalDateTime createdAt;

        public static AttachmentDTO from(Attachment a, String urlPrefix) {
            AttachmentDTO d = new AttachmentDTO();
            d.id = a.getId();
            d.itemId = a.getItemId();
            d.fileName = a.getFileName();
            d.storedName = a.getStoredName();
            d.filePath = a.getFilePath();
            d.url = urlPrefix + "/" + a.getFilePath();
            d.downloadUrl = urlPrefix + "/" + a.getFilePath() + "?download=true";
            d.fileSize = a.getFileSize();
            d.readableSize = a.readableSize();
            d.mimeType = a.getMimeType();
            d.mediaType = a.getMediaType();
            d.extension = a.getExtension();
            d.extractStatus = a.getExtractStatus();
            d.textLength = a.getExtractedText() == null ? 0 : a.getExtractedText().length();
            d.durationSec = a.getDurationSec();
            d.createdAt = a.getCreatedAt();
            return d;
        }
    }
}
