package cn.edu.njupt.kms.module.stats.service;

import cn.edu.njupt.kms.module.audit.entity.AuditLog;
import cn.edu.njupt.kms.module.audit.repository.AuditLogRepository;
import cn.edu.njupt.kms.module.cms.repository.ItemVersionRepository;
import cn.edu.njupt.kms.module.kb.entity.Category;
import cn.edu.njupt.kms.module.kb.entity.KnowledgeItem;
import cn.edu.njupt.kms.module.kb.repository.AttachmentRepository;
import cn.edu.njupt.kms.module.kb.repository.CategoryRepository;
import cn.edu.njupt.kms.module.kb.repository.ItemTagLinkRepository;
import cn.edu.njupt.kms.module.kb.repository.KnowledgeItemRepository;
import cn.edu.njupt.kms.module.kb.repository.TagRepository;
import cn.edu.njupt.kms.module.ontology.entity.Concept;
import cn.edu.njupt.kms.module.ontology.repository.ConceptRepository;
import cn.edu.njupt.kms.module.ontology.service.OntologyService;
import cn.edu.njupt.kms.module.rag.repository.RagChunkRepository;
import cn.edu.njupt.kms.module.search.service.SearchService;
import cn.edu.njupt.kms.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计看板服务：为首页与答辩演示提供全局数据视图。
 *
 * <p>所有统计都是实时聚合，不额外维护汇总表 —— 课程设计的数据规模下
 * 这是最不易出错的做法（冗余计数一旦漂移，排查成本远高于一次 COUNT）。</p>
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final KnowledgeItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final ItemTagLinkRepository tagLinkRepository;
    private final AttachmentRepository attachmentRepository;
    private final ConceptRepository conceptRepository;
    private final ItemVersionRepository versionRepository;
    private final RagChunkRepository chunkRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final OntologyService ontologyService;
    private final SearchService searchService;

    /** 首页总览。 */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();

        m.put("knowledgeCount", itemRepository.countByDeleted(0));
        m.put("publishedCount", itemRepository.countByStatusAndDeleted(KnowledgeItem.PUBLISHED, 0));
        m.put("draftCount", itemRepository.countByStatusAndDeleted(KnowledgeItem.DRAFT, 0));
        m.put("pendingCount", itemRepository.countByStatusAndDeleted(KnowledgeItem.PENDING, 0));
        m.put("archivedCount", itemRepository.countByStatusAndDeleted(KnowledgeItem.ARCHIVED, 0));

        m.put("categoryCount", categoryRepository.count());
        m.put("tagCount", tagRepository.count());
        m.put("attachmentCount", attachmentRepository.count());
        m.put("videoCount", attachmentRepository.countByMediaType("VIDEO"));
        m.put("imageCount", attachmentRepository.countByMediaType("IMAGE"));
        m.put("documentCount", attachmentRepository.countByMediaType("DOCUMENT"));

        m.put("userCount", userRepository.count());
        m.put("versionCount", versionRepository.countAll());
        m.put("chunkCount", chunkRepository.countAll());

        var ontology = ontologyService.stats();
        m.put("conceptCount", ontology.getConceptCount());
        m.put("relationCount", ontology.getRelationCount());
        m.put("relationTypeCount", ontology.getRelationTypeCount());
        m.put("ontologyCoverage", ontology.getCoverage());
        m.put("ontologyMaxDepth", ontology.getMaxDepth());

        m.put("todayNewCount", itemRepository.countByPublishedAtAfterAndDeleted(
                LocalDate.now().atStartOfDay(), 0));
        m.put("weekNewCount", itemRepository.countByPublishedAtAfterAndDeleted(
                LocalDate.now().minusDays(7).atStartOfDay(), 0));

        return m;
    }

    /** 分布类图表数据。 */
    @Transactional(readOnly = true)
    public Map<String, Object> distribution() {
        Map<String, Object> m = new LinkedHashMap<>();

        // 按资产类型
        m.put("assetTypes", itemRepository.countGroupByAssetType().stream()
                .map(r -> Map.of(
                        "name", assetTypeName((String) r[0]),
                        "key", r[0] == null ? "OTHER" : r[0],
                        "value", ((Number) r[1]).longValue()))
                .toList());

        // 按状态
        m.put("statuses", itemRepository.countGroupByStatus().stream()
                .map(r -> Map.of(
                        "name", statusName((String) r[0]),
                        "key", r[0] == null ? "UNKNOWN" : r[0],
                        "value", ((Number) r[1]).longValue()))
                .toList());

        // 按分类（取前 12）
        Map<Long, String> categoryNames = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        m.put("categories", itemRepository.countGroupByCategory().stream()
                .filter(r -> r[0] != null)
                .sorted((a, b) -> Long.compare(((Number) b[1]).longValue(), ((Number) a[1]).longValue()))
                .limit(12)
                .map(r -> Map.of(
                        "name", categoryNames.getOrDefault(((Number) r[0]).longValue(), "未分类"),
                        "value", ((Number) r[1]).longValue()))
                .toList());

        // 按知识域
        m.put("domains", conceptRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        c -> c.getDomain() == null ? "未分类" : c.getDomain(),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> Map.of("name", e.getKey(), "value", e.getValue()))
                .toList());

        // 热门标签
        Map<Long, String> tagNames = tagRepository.findAll().stream()
                .collect(Collectors.toMap(t -> t.getId(), t -> t.getName()));
        m.put("tags", tagLinkRepository.countGroupByTag().stream()
                .limit(15)
                .map(r -> Map.of(
                        "name", tagNames.getOrDefault(((Number) r[0]).longValue(), "未知"),
                        "value", ((Number) r[1]).longValue()))
                .toList());

        return m;
    }

    /** 近 N 天的发布趋势。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> trend(int days) {
        int n = Math.min(Math.max(days, 3), 90);
        LocalDate from = LocalDate.now().minusDays(n - 1L);

        Map<LocalDate, Long> created = itemRepository.findAll().stream()
                .filter(i -> i.getDeleted() == null || i.getDeleted() == 0)
                .filter(i -> i.getCreatedAt() != null && !i.getCreatedAt().toLocalDate().isBefore(from))
                .collect(Collectors.groupingBy(i -> i.getCreatedAt().toLocalDate(), Collectors.counting()));

        // 与「新增」口径保持一致：必须排除逻辑删除的条目。
        // 否则冒烟测试创建后又删除的条目会残留在发布曲线上，两条线对不上。
        Map<LocalDate, Long> published = itemRepository.findAll().stream()
                .filter(i -> i.getDeleted() == null || i.getDeleted() == 0)
                .filter(i -> i.getPublishedAt() != null && !i.getPublishedAt().toLocalDate().isBefore(from))
                .collect(Collectors.groupingBy(i -> i.getPublishedAt().toLocalDate(), Collectors.counting()));

        List<Map<String, Object>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LocalDate d = from.plusDays(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("created", created.getOrDefault(d, 0L));
            row.put("published", published.getOrDefault(d, 0L));
            out.add(row);
        }
        return out;
    }

    /** 首页侧栏：最新动态。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentActivity(int limit) {
        return auditLogRepository.findTop20ByOrderByIdDesc().stream()
                .limit(limit)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("username", a.getUsername());
                    m.put("action", a.getAction());
                    m.put("actionName", actionName(a.getAction()));
                    m.put("targetType", a.getTargetType());
                    m.put("targetId", a.getTargetId());
                    m.put("detail", a.getDetail());
                    m.put("createdAt", a.getCreatedAt());
                    return m;
                })
                .toList();
    }

    /** 知识地图：每个知识点下的条目数（用于首页热力展示）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> conceptHeat(int limit) {
        return conceptRepository.findAll().stream()
                .sorted(Comparator.comparing(Concept::getImportance,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("name", c.getName());
                    m.put("domain", c.getDomain());
                    m.put("level", c.getLevel());
                    m.put("difficulty", c.getDifficulty());
                    return m;
                })
                .toList();
    }

    private String assetTypeName(String type) {
        return cn.edu.njupt.kms.module.kb.dto.KnowledgeDTO.ItemDTO.assetTypeName(type);
    }

    private String statusName(String status) {
        return cn.edu.njupt.kms.module.kb.dto.KnowledgeDTO.ItemDTO.statusName(status);
    }

    private String actionName(String action) {
        if (action == null) {
            return "未知操作";
        }
        return switch (action) {
            case "CREATE" -> "创建";
            case "UPDATE" -> "更新";
            case "DELETE" -> "删除";
            case "PUBLISH" -> "发布";
            case "UNPUBLISH" -> "撤回发布";
            case "SUBMIT" -> "提交审核";
            case "ARCHIVE" -> "归档";
            case "UNARCHIVE" -> "恢复归档";
            case "ROLLBACK" -> "版本回滚";
            case "CREATE_VERSION" -> "创建版本";
            case "LOGIN" -> "登录";
            case "REGISTER" -> "注册";
            case "CHANGE_PASSWORD" -> "修改口令";
            case "UPLOAD" -> "上传文件";
            default -> action;
        };
    }
}
