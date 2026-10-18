package cn.edu.njupt.kms.module.cms.dto;

import cn.edu.njupt.kms.module.cms.entity.ItemVersion;
import cn.edu.njupt.kms.module.cms.entity.VersionPolicy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** CMS 版本管理模块的请求/响应对象集合。 */
public final class CmsDTO {

    private CmsDTO() {
    }

    // ---------------- 版本 ----------------

    @Data
    public static class VersionDTO {
        private Long id;
        private Long itemId;
        private Integer versionNo;
        private String title;
        private String summary;
        /** 是否返回正文全文（列表页默认不返回，详情页才返回） */
        private String content;
        private Long categoryId;
        private String status;
        private String assetType;
        private List<String> conceptNames;
        private List<String> tags;
        private String changeLog;
        private String changeSource;
        private String changeSourceName;
        private Integer charAdded;
        private Integer charRemoved;
        private String contentHash;
        private Long editorId;
        private String editorName;
        private LocalDateTime createdAt;
        /** 正文长度，列表页展示用 */
        private Integer contentLength;
        /** 是否为当前生效版本 */
        private Boolean current;

        public static VersionDTO from(ItemVersion v, boolean withContent) {
            VersionDTO d = new VersionDTO();
            d.id = v.getId();
            d.itemId = v.getItemId();
            d.versionNo = v.getVersionNo();
            d.title = v.getTitle();
            d.summary = v.getSummary();
            if (withContent) {
                d.content = v.getContent();
            }
            d.categoryId = v.getCategoryId();
            d.status = v.getStatus();
            d.assetType = v.getAssetType();
            d.conceptNames = parseConcepts(v.getConceptsSnapshot());
            d.tags = splitCsv(v.getTagsSnapshot());
            d.changeLog = v.getChangeLog();
            d.changeSource = v.getChangeSource();
            d.changeSourceName = sourceName(v.getChangeSource());
            d.charAdded = v.getCharAdded();
            d.charRemoved = v.getCharRemoved();
            d.contentHash = v.getContentHash();
            d.editorId = v.getEditorId();
            d.editorName = v.getEditorName();
            d.createdAt = v.getCreatedAt();
            d.contentLength = v.getContent() == null ? 0 : v.getContent().length();
            return d;
        }

        public static String sourceName(String source) {
            if (source == null) {
                return "未知";
            }
            return switch (source) {
                case "AUTO" -> "自动快照";
                case "MANUAL" -> "手工创建";
                case "PUBLISH" -> "发布触发";
                case "ROLLBACK" -> "回滚生成";
                default -> source;
            };
        }

        /** 概念快照格式：{@code id:name|id:name}。 */
        static List<String> parseConcepts(String snapshot) {
            if (snapshot == null || snapshot.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(snapshot.split("\\|"))
                    .map(s -> {
                        int idx = s.indexOf(':');
                        return idx >= 0 ? s.substring(idx + 1) : s;
                    })
                    .filter(s -> !s.isBlank())
                    .toList();
        }

        static List<String> splitCsv(String s) {
            if (s == null || s.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        }
    }

    /** 两个版本之间的差异。 */
    @Data
    public static class VersionDiffDTO {
        private Integer fromVersion;
        private Integer toVersion;
        private String fromTitle;
        private String toTitle;
        private int charAdded;
        private int charRemoved;
        private boolean titleChanged;
        private boolean summaryChanged;
        private boolean categoryChanged;
        private List<String> conceptsAdded;
        private List<String> conceptsRemoved;
        private List<String> tagsAdded;
        private List<String> tagsRemoved;
        /** 行级差异：type = ADD / REMOVE / SAME / CHANGE */
        private List<DiffLine> lines;
        private int addedLines;
        private int removedLines;
    }

    @Data
    public static class DiffLine {
        private String type;
        private Integer oldLineNo;
        private Integer newLineNo;
        private String content;

        public static DiffLine of(String type, Integer oldNo, Integer newNo, String content) {
            DiffLine l = new DiffLine();
            l.type = type;
            l.oldLineNo = oldNo;
            l.newLineNo = newNo;
            l.content = content;
            return l;
        }
    }

    @Data
    public static class VersionCreateRequest {
        @NotBlank(message = "版本说明不能为空")
        private String changeLog;
        /** 是否把该版本设为当前内容（即回滚式保存） */
        private Boolean applyToItem = false;
    }

    // ---------------- 策略 ----------------

    @Data
    public static class VersionPolicyDTO {
        private Long id;
        private String scope;
        private String scopeName;
        private Long scopeId;
        private String targetName;
        private String policyName;
        private Integer intervalDays;
        private Integer minChangeCount;
        private Integer maxVersions;
        private Integer autoSnapshot;
        private Integer enabled;
        private Integer priority;
        private LocalDateTime updatedAt;

        public static VersionPolicyDTO from(VersionPolicy p) {
            VersionPolicyDTO d = new VersionPolicyDTO();
            d.id = p.getId();
            d.scope = p.getScope();
            d.scopeName = switch (p.getScope() == null ? "" : p.getScope()) {
                case "GLOBAL" -> "全局";
                case "CATEGORY" -> "分类级";
                case "ITEM" -> "条目级";
                default -> p.getScope();
            };
            d.scopeId = p.getScopeId();
            d.policyName = p.getPolicyName();
            d.intervalDays = p.getIntervalDays();
            d.minChangeCount = p.getMinChangeCount();
            d.maxVersions = p.getMaxVersions();
            d.autoSnapshot = p.getAutoSnapshot();
            d.enabled = p.getEnabled();
            d.priority = p.getPriority();
            d.updatedAt = p.getUpdatedAt();
            return d;
        }
    }

    @Data
    public static class VersionPolicySaveRequest {
        @NotBlank(message = "策略名称不能为空")
        private String policyName;

        @NotBlank(message = "作用域不能为空")
        private String scope = "GLOBAL";

        private Long scopeId;

        @Min(value = 0, message = "天数阈值不能为负")
        private Integer intervalDays = 1;

        @Min(value = 1, message = "修改次数阈值至少为 1")
        private Integer minChangeCount = 5;

        @Min(value = 1, message = "保留版本数至少为 1")
        private Integer maxVersions = 50;

        private Integer autoSnapshot = 1;
        private Integer enabled = 1;
        private Integer priority = 0;
    }

    /** 策略判定结果：解释“为什么现在该/不该建版本”。 */
    @Data
    public static class SnapshotDecisionDTO {
        private boolean due;
        private String reason;
        private Integer intervalDays;
        private Integer minChangeCount;
        private long daysSinceLastSnapshot;
        private Integer pendingChangeCount;
        private Long policyId;
        private String policyName;
    }
}
