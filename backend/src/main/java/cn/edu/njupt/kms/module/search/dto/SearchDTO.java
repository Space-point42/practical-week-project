package cn.edu.njupt.kms.module.search.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 搜索引擎模块的请求/响应对象集合。 */
public final class SearchDTO {

    private SearchDTO() {
    }

    /** 检索方式。 */
    public static final class Mode {
        /** 自动：有关键词走全文索引，无关键词走条件过滤 */
        public static final String AUTO = "AUTO";
        /** 强制全文检索（MySQL ngram 全文索引 + 相关性排序） */
        public static final String FULLTEXT = "FULLTEXT";
        /** 高级检索：多条件组合过滤 */
        public static final String ADVANCED = "ADVANCED";

        private Mode() {
        }
    }

    /** 检索请求。 */
    @Data
    public static class SearchRequest {
        /** 查询串；为空时退化为纯条件过滤。 */
        private String keyword;

        private String mode = Mode.AUTO;

        // ---- 高级检索条件 ----
        private Long categoryId;
        private List<String> assetTypes;
        private List<Long> tagIds;
        private List<Long> conceptIds;
        private Long authorId;
        private LocalDateTime createdFrom;
        private LocalDateTime createdTo;
        /** 仅搜索标题 */
        private Boolean titleOnly = false;
        /** 仅搜索含附件的条目 */
        private Boolean withAttachmentOnly = false;

        // ---- 排序与分页 ----
        /** RELEVANCE 相关性 / LATEST 最新 / HOTTEST 最热 / LIKES 最多点赞 */
        private String sortBy = "RELEVANCE";
        private int page = 1;
        private int size = 10;
    }

    /** 检索响应。 */
    @Data
    public static class SearchResult {
        private List<Hit> hits;
        private long total;
        private int page;
        private int size;
        private int pages;
        /** 本次检索实际使用的通道与耗时，便于答辩演示时解释实现 */
        private String modeUsed;
        private long costMs;
        /** MySQL 全文索引是否命中（为 false 时说明走了降级路径） */
        private boolean fulltextUsed;
        /** 查询词的分词结果 */
        private List<String> terms;
        /** 由查询串链接到的本体知识点 */
        private List<LinkedConcept> linkedConcepts;
        /** 检索建议（无结果或仅有弱匹配时给出） */
        private List<String> suggestions;

        /**
         * 是否为弱匹配结果集。
         *
         * <p>当没有任何一条结果在标题、摘要或关键词上命中查询词时置为 true，
         * 说明这些结果只是 ngram 二元组的偶然重合（例如查询里的「存在」
         * 恰好出现在别的文档中）。界面应据此提示「未找到精确匹配，以下为模糊结果」，
         * 而不是把弱匹配伪装成精确匹配。</p>
         */
        private boolean weakMatch;
    }

    /** 单条命中。 */
    @Data
    public static class Hit {
        private Long id;
        private String title;
        private String titleHighlight;
        private String summary;
        private String snippet;
        private String assetType;
        private String assetTypeName;
        private String status;
        private Long categoryId;
        private String categoryName;
        private String authorName;
        private List<String> tags;
        private List<String> conceptNames;
        private List<AttachmentBrief> videos;
        private List<AttachmentBrief> images;
        private Integer viewCount;
        private Integer likeCount;
        private Integer versionNo;
        private LocalDateTime publishedAt;
        private LocalDateTime updatedAt;
        /** 相关性得分（归一化到 0~1） */
        private Double score;
        /** 得分来源说明，如「字段命中 标题 100%/摘要 60% ×0.65 + ...」 */
        private String scoreDetail;
        /** 标题中是否命中了查询词（界面上可据此打「强匹配」标记） */
        private Boolean titleMatch;
        /** 摘要或关键词中是否命中了查询词 */
        private Boolean summaryMatch;
    }

    @Data
    public static class AttachmentBrief {
        private Long id;
        private String fileName;
        private String url;
        private String mediaType;
        private String coverImage;

        public static AttachmentBrief of(Long id, String fileName, String url, String mediaType, String cover) {
            AttachmentBrief b = new AttachmentBrief();
            b.id = id;
            b.fileName = fileName;
            b.url = url;
            b.mediaType = mediaType;
            b.coverImage = cover;
            return b;
        }
    }

    @Data
    public static class LinkedConcept {
        private Long id;
        private String name;
        private String domain;
    }

    /** 搜索联想。 */
    @Data
    public static class Suggestion {
        private String text;
        /** HISTORY 历史搜索词 / CONCEPT 知识点 / TITLE 条目标题 / TAG 标签 */
        private String type;
        private Long refId;
    }

    /** 热搜词。 */
    @Data
    public static class HotKeyword {
        private String keyword;
        private long count;

        public HotKeyword(String keyword, long count) {
            this.keyword = keyword;
            this.count = count;
        }
    }

    /** 检索统计（管理员看板）。 */
    @Data
    public static class SearchStats {
        private long totalSearches;
        private double averageCostMs;
        private List<HotKeyword> hotKeywords;
        private List<HotKeyword> noResultKeywords;
    }
}
