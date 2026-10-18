package cn.edu.njupt.kms.module.ontology.dto;

import cn.edu.njupt.kms.module.ontology.entity.Concept;
import cn.edu.njupt.kms.module.ontology.entity.Relation;
import cn.edu.njupt.kms.module.ontology.entity.RelationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 本体模块的请求/响应对象集合。 */
public final class OntologyDTO {

    private OntologyDTO() {
    }

    // ---------------- 概念 ----------------

    @Data
    public static class ConceptDTO {
        private Long id;
        private String code;
        private String name;
        private String enName;
        private List<String> aliases;
        private String description;
        private Long parentId;
        private String parentName;
        private Integer level;
        private String path;
        private String domain;
        private Integer difficulty;
        private Integer importance;
        private String color;
        private Integer builtin;
        private Integer sortOrder;
        /** 关联的知识条目数 */
        private long itemCount;
        /** 直接子概念数 */
        private long childCount;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static ConceptDTO from(Concept c) {
            ConceptDTO d = new ConceptDTO();
            d.id = c.getId();
            d.code = c.getCode();
            d.name = c.getName();
            d.enName = c.getEnName();
            d.aliases = splitAliases(c.getAliases());
            d.description = c.getDescription();
            d.parentId = c.getParentId();
            d.level = c.getLevel();
            d.path = c.getPath();
            d.domain = c.getDomain();
            d.difficulty = c.getDifficulty();
            d.importance = c.getImportance();
            d.color = c.getColor();
            d.builtin = c.getBuiltin();
            d.sortOrder = c.getSortOrder();
            d.createdAt = c.getCreatedAt();
            d.updatedAt = c.getUpdatedAt();
            return d;
        }

        public static List<String> splitAliases(String s) {
            if (s == null || s.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(s.split(","))
                    .map(String::trim).filter(x -> !x.isEmpty()).toList();
        }
    }

    @Data
    public static class ConceptSaveRequest {
        @NotBlank(message = "概念编码不能为空")
        @Size(max = 64)
        private String code;

        @NotBlank(message = "概念名称不能为空")
        @Size(max = 128)
        private String name;

        private String enName;
        private List<String> aliases;
        private String description;
        private Long parentId;
        private String domain;
        private Integer difficulty = 3;
        private Integer importance = 3;
        private String color;
        private Integer sortOrder;
    }

    // ---------------- 关系 ----------------

    @Data
    public static class RelationTypeDTO {
        private Long id;
        private String code;
        private String name;
        private String inverseCode;
        private Integer directed;
        private String color;
        private String description;
        private Integer sortOrder;
        /** 该类型的关系数量 */
        private long count;

        public static RelationTypeDTO from(RelationType t) {
            RelationTypeDTO d = new RelationTypeDTO();
            d.id = t.getId();
            d.code = t.getCode();
            d.name = t.getName();
            d.inverseCode = t.getInverseCode();
            d.directed = t.getDirected();
            d.color = t.getColor();
            d.description = t.getDescription();
            d.sortOrder = t.getSortOrder();
            return d;
        }
    }

    @Data
    public static class RelationDTO {
        private Long id;
        private Long sourceId;
        private String sourceName;
        private String sourceCode;
        private Long targetId;
        private String targetName;
        private String targetCode;
        private String relationType;
        private String relationTypeName;
        private Double weight;
        private String description;
        /** 便于界面直接显示的自然语言断言：源 + 关系 + 目标 */
        private String statement;

        public static RelationDTO from(Relation r, Concept source, Concept target, String typeName) {
            RelationDTO d = new RelationDTO();
            d.id = r.getId();
            d.sourceId = r.getSourceId();
            d.targetId = r.getTargetId();
            d.relationType = r.getRelationType();
            d.relationTypeName = typeName;
            d.weight = r.getWeight() == null ? null : r.getWeight().doubleValue();
            d.description = r.getDescription();
            if (source != null) {
                d.sourceName = source.getName();
                d.sourceCode = source.getCode();
            }
            if (target != null) {
                d.targetName = target.getName();
                d.targetCode = target.getCode();
            }
            if (source != null && target != null) {
                d.statement = "%s ——【%s】——> %s".formatted(
                        source.getName(), typeName == null ? r.getRelationType() : typeName, target.getName());
            }
            return d;
        }
    }

    @Data
    public static class RelationSaveRequest {
        private Long sourceId;
        private Long targetId;
        @NotBlank(message = "关系类型不能为空")
        private String relationType;
        private Double weight = 1.0;
        private String description;
    }

    // ---------------- 图谱 ----------------

    @Data
    public static class GraphNode {
        private Long id;
        private String code;
        private String name;
        private String domain;
        private Integer level;
        private Integer difficulty;
        private String color;
        /** 图谱中的可视化规模权重（按关联条目数取对数） */
        private double value;
        private String category;
        private long itemCount;
        /** 是否在本次查询中作为命中节点被强调 */
        private boolean highlight;
    }

    @Data
    public static class GraphEdge {
        private Long id;
        private Long source;
        private Long target;
        private String relationType;
        private String relationTypeName;
        private String label;
        private Double weight;
        private String color;
    }

    @Data
    public static class GraphDTO {
        private List<GraphNode> nodes;
        private List<GraphEdge> edges;
        private List<RelationTypeDTO> relationTypes;
        private int nodeCount;
        private int edgeCount;
    }

    // ---------------- 统计 / 其他 ----------------

    @Data
    public static class OntologyStatsDTO {
        /** 概念总数（知识点总数） */
        private long conceptCount;
        /** 关系总数 */
        private long relationCount;
        /** 关系类型数 */
        private long relationTypeCount;
        /** 层次最大深度 */
        private int maxDepth;
        /** 各知识域的概念数 */
        private Map<String, Long> domainDistribution;
        /** 各关系类型的数量 */
        private Map<String, Long> relationTypeDistribution;
        /** 已被知识条目引用的概念数（本体覆盖率分子） */
        private long coveredConceptCount;
        /** 本体覆盖率 = 有知识条目支撑的概念 / 概念总数 */
        private double coverage;
    }

    /** 本体推理结果：沿关系路径可达的概念。 */
    @Data
    public static class InferenceDTO {
        private Long originId;
        private String originName;
        private String relationType;
        private int maxDepth;
        /** 逐层展开的推理结果 */
        private List<InferenceLevel> levels;
    }

    @Data
    public static class InferenceLevel {
        private int depth;
        private List<ConceptDTO> concepts;
        private List<String> paths;
    }
}
