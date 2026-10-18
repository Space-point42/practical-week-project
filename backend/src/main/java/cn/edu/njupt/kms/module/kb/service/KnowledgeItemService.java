package cn.edu.njupt.kms.module.kb.service;

import cn.edu.njupt.kms.common.BizException;
import cn.edu.njupt.kms.common.PageResult;
import cn.edu.njupt.kms.common.event.ItemChangedEvent;
import cn.edu.njupt.kms.module.audit.service.AuditService;
import cn.edu.njupt.kms.module.cms.entity.ItemVersion;
import cn.edu.njupt.kms.module.cms.service.VersionPolicyService;
import cn.edu.njupt.kms.module.cms.service.VersionService;
import cn.edu.njupt.kms.module.kb.dto.KnowledgeDTO;
import cn.edu.njupt.kms.module.kb.entity.*;
import cn.edu.njupt.kms.module.kb.repository.*;
import cn.edu.njupt.kms.module.ontology.entity.Concept;
import cn.edu.njupt.kms.module.ontology.repository.ConceptRepository;
import cn.edu.njupt.kms.module.ontology.service.OntologyService;
import cn.edu.njupt.kms.module.user.entity.User;
import cn.edu.njupt.kms.module.user.repository.UserRepository;
import cn.edu.njupt.kms.security.SecurityUtils;
import cn.edu.njupt.kms.util.TextUtils;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识条目服务 —— 知识库的核心业务。
 *
 * <p>一条知识条目的完整生命周期：</p>
 * <pre>
 *   创建/编辑 → 自动抽取正文与关键词、自动标注本体概念、按策略建版
 *        ↓
 *   提交审核（PENDING）→ 发布（PUBLISHED，生成发布版本、触发 RAG 索引）
 *        ↓
 *   归档（ARCHIVED，保留但不再出现在常规检索中）→ 可恢复
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeItemService {

    private final KnowledgeItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final ItemTagLinkRepository tagLinkRepository;
    private final ItemConceptLinkRepository conceptLinkRepository;
    private final AttachmentRepository attachmentRepository;
    private final ConceptRepository conceptRepository;
    private final UserRepository userRepository;

    private final CategoryService categoryService;
    private final OntologyService ontologyService;
    private final VersionService versionService;
    private final VersionPolicyService policyService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    // ==================================================================
    // 写入
    // ==================================================================

    /** 新建知识条目（初始为草稿）。 */
    @Transactional
    public KnowledgeDTO.ItemDTO create(KnowledgeDTO.ItemSaveRequest req) {
        KnowledgeItem item = new KnowledgeItem();
        item.setStatus(KnowledgeItem.DRAFT);
        item.setAuthorId(SecurityUtils.currentUserId());
        item.setVersionNo(0);          // 首个版本快照会在保存后生成为 v1
        item.setChangeCount(0);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());

        applyFields(item, req);
        item.setPlainText(buildPlainText(item, req));
        item.setKeywords(String.join(",", TextUtils.extractKeywords(item.getPlainText(), item.getTitle(), 8)));

        itemRepository.save(item);

        // 首次创建立即生成 v1 基线版本，保证任何条目都有可回滚的起点
        versionService.snapshot(item, ItemVersion.SOURCE_MANUAL, "创建条目，生成初始版本");

        replaceTags(item.getId(), req.getTags());
        replaceConcepts(item.getId(), req.getConceptIds(), req.getAutoTagConcepts());
        bindAttachments(item.getId(), req.getAttachmentIds());
        refreshPlainTextWithAttachments(item);

        adjustCategoryCount(item.getCategoryId(), +1);

        auditService.record("CREATE", "ITEM", item.getId(), "新建知识条目：" + item.getTitle());
        eventPublisher.publishEvent(ItemChangedEvent.saved(item.getId()));
        return detail(item.getId(), false);
    }

    /**
     * 更新知识条目。
     *
     * <p>保存时会累加 {@code changeCount}，并按版本策略判断是否需要生成新快照。</p>
     */
    @Transactional
    public KnowledgeDTO.ItemDTO update(Long id, KnowledgeDTO.ItemSaveRequest req) {
        KnowledgeItem item = require(id);
        SecurityUtils.requireOwnerOrAdmin(item.getAuthorId());

        if (KnowledgeItem.ARCHIVED.equals(item.getStatus())) {
            throw BizException.badRequest("已归档的条目不可直接编辑，请先恢复");
        }

        // applyFields 会覆盖 categoryId，因此先记下原分类，供后面维护分类计数
        Long previousCategoryId = item.getCategoryId();

        applyFields(item, req);
        item.setPlainText(buildPlainText(item, req));
        item.setKeywords(String.join(",", TextUtils.extractKeywords(item.getPlainText(), item.getTitle(), 8)));
        item.setChangeCount((item.getChangeCount() == null ? 0 : item.getChangeCount()) + 1);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);

        replaceTags(item.getId(), req.getTags());
        replaceConcepts(item.getId(), req.getConceptIds(), req.getAutoTagConcepts());
        bindAttachments(item.getId(), req.getAttachmentIds());
        refreshPlainTextWithAttachments(item);

        // 分类变了：原分类减一、新分类加一；未变则不动
        if (!java.util.Objects.equals(previousCategoryId, item.getCategoryId())) {
            adjustCategoryCount(previousCategoryId, -1);
            adjustCategoryCount(item.getCategoryId(), +1);
        }

        // 按策略决定是否落一个版本快照
        ItemVersion v = versionService.autoSnapshot(item, req.getChangeLog());
        if (v != null) {
            auditService.record("CREATE_VERSION", "ITEM", id,
                    "自动生成版本 v%d：%s".formatted(v.getVersionNo(), v.getChangeLog()));
        }

        auditService.record("UPDATE", "ITEM", id, "更新知识条目：" + item.getTitle());
        eventPublisher.publishEvent(ItemChangedEvent.saved(id));
        return detail(id, false);
    }

    /** 提交审核：草稿 → 待审核。 */
    @Transactional
    public KnowledgeDTO.ItemDTO submit(Long id) {
        KnowledgeItem item = require(id);
        SecurityUtils.requireOwnerOrAdmin(item.getAuthorId());
        if (!KnowledgeItem.DRAFT.equals(item.getStatus())) {
            throw BizException.badRequest("只有草稿状态的条目才能提交审核");
        }
        item.setStatus(KnowledgeItem.PENDING);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
        auditService.record("SUBMIT", "ITEM", id, "提交审核：" + item.getTitle());
        return detail(id, false);
    }

    /** 发布：待审核/草稿 → 已发布，并生成发布版本、触发索引重建。 */
    @Transactional
    public KnowledgeDTO.ItemDTO publish(Long id, String changeLog) {
        KnowledgeItem item = require(id);
        if (KnowledgeItem.PUBLISHED.equals(item.getStatus())) {
            throw BizException.badRequest("条目已处于发布状态");
        }
        if (KnowledgeItem.ARCHIVED.equals(item.getStatus())) {
            throw BizException.badRequest("已归档的条目不可发布，请先恢复");
        }
        if (!StringUtils.hasText(item.getContent()) && attachmentRepository.countByItemId(id) == 0) {
            throw BizException.badRequest("条目既无正文也无附件，无法发布");
        }

        item.setStatus(KnowledgeItem.PUBLISHED);
        item.setPublishedAt(LocalDateTime.now());
        item.setArchivedAt(null);
        item.setArchiveReason(null);
        item.setChangeCount((item.getChangeCount() == null ? 0 : item.getChangeCount()) + 1);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);

        versionService.snapshot(item, ItemVersion.SOURCE_PUBLISH,
                StringUtils.hasText(changeLog) ? changeLog : "发布条目");

        auditService.record("PUBLISH", "ITEM", id, "发布知识条目：" + item.getTitle());
        eventPublisher.publishEvent(ItemChangedEvent.published(id));
        return detail(id, false);
    }

    /** 撤回发布，回到草稿。 */
    @Transactional
    public KnowledgeDTO.ItemDTO unpublish(Long id) {
        KnowledgeItem item = require(id);
        if (!KnowledgeItem.PUBLISHED.equals(item.getStatus())) {
            throw BizException.badRequest("只有已发布的条目才能撤回");
        }
        item.setStatus(KnowledgeItem.DRAFT);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
        auditService.record("UNPUBLISH", "ITEM", id, "撤回发布：" + item.getTitle());
        return detail(id, false);
    }

    /** 归档：保留内容但移出常规检索范围。 */
    @Transactional
    public KnowledgeDTO.ItemDTO archive(Long id, String reason) {
        KnowledgeItem item = require(id);
        if (KnowledgeItem.ARCHIVED.equals(item.getStatus())) {
            throw BizException.badRequest("条目已处于归档状态");
        }
        item.setStatus(KnowledgeItem.ARCHIVED);
        item.setArchivedAt(LocalDateTime.now());
        item.setArchiveReason(reason);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
        auditService.record("ARCHIVE", "ITEM", id,
                "归档条目：%s（原因：%s）".formatted(item.getTitle(), reason == null ? "未填写" : reason));
        eventPublisher.publishEvent(ItemChangedEvent.archived(id));
        return detail(id, false);
    }

    /** 从归档中恢复。 */
    @Transactional
    public KnowledgeDTO.ItemDTO unarchive(Long id) {
        KnowledgeItem item = require(id);
        if (!KnowledgeItem.ARCHIVED.equals(item.getStatus())) {
            throw BizException.badRequest("条目未处于归档状态");
        }
        // 恢复后回到草稿，由编辑确认内容仍有效后再发布
        item.setStatus(KnowledgeItem.DRAFT);
        item.setArchivedAt(null);
        item.setArchiveReason(null);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
        auditService.record("UNARCHIVE", "ITEM", id, "恢复归档条目：" + item.getTitle());
        eventPublisher.publishEvent(ItemChangedEvent.saved(id));
        return detail(id, false);
    }

    /** 逻辑删除。 */
    @Transactional
    public void delete(Long id) {
        KnowledgeItem item = require(id);
        SecurityUtils.requireOwnerOrAdmin(item.getAuthorId());
        item.setDeleted(1);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
        adjustCategoryCount(item.getCategoryId(), -1);
        auditService.record("DELETE", "ITEM", id, "删除知识条目：" + item.getTitle());
        eventPublisher.publishEvent(ItemChangedEvent.deleted(id));
    }

    /**
     * 维护分类上的条目计数（冗余展示字段）。
     *
     * <p>该字段存在的意义是让分类树不必每次都用 COUNT 聚合就能显示条目数。
     * 冗余字段的风险是会漂移，因此必须在条目增删改的**每一条**路径上都同步调整 ——
     * 这正是此前遗漏的地方：增量调整方法早已定义，却从未被调用，
     * 导致新建条目后分类树的数字不更新，只能靠管理员手动触发全量重算。</p>
     *
     * <p>计数口径与 {@code recalculateItemCounts} 保持一致：只统计未逻辑删除的条目，
     * 与条目处于草稿还是已发布无关。因此归档/恢复、提交审核、发布等状态流转都不需要调整计数。</p>
     *
     * @param categoryId 目标分类，为空表示条目未归类，无需调整
     * @param delta      +1 表示新增，-1 表示移除
     */
    private void adjustCategoryCount(Long categoryId, int delta) {
        if (categoryId == null) {
            return;
        }
        categoryRepository.addItemCount(categoryId, delta);
    }

    /** 点赞 / 取消点赞。 */
    @Transactional
    public int like(Long id, boolean liked) {
        require(id);
        itemRepository.addLikeCount(id, liked ? 1 : -1);
        return itemRepository.findById(id).map(KnowledgeItem::getLikeCount).orElse(0);
    }

    // ==================================================================
    // 读取
    // ==================================================================

    /** 详情；{@code countView=true} 时累加浏览次数。 */
    @Transactional
    public KnowledgeDTO.ItemDTO detail(Long id, boolean countView) {
        KnowledgeItem item = require(id);
        if (countView) {
            itemRepository.incrementViewCount(id);
            item.setViewCount(item.getViewCount() == null ? 1 : item.getViewCount() + 1);
        }
        return enrich(KnowledgeDTO.ItemDTO.detail(item), item);
    }

    /** 管理员/作者的完整列表（含草稿、待审、归档）。 */
    @Transactional(readOnly = true)
    public PageResult<KnowledgeDTO.ItemDTO> page(KnowledgeDTO.ItemQuery q, boolean includeUnpublished) {
        Page<KnowledgeItem> page = findItems(q, includeUnpublished);
        return PageResult.of(page, i -> enrich(KnowledgeDTO.ItemDTO.from(i), i));
    }

    /**
     * 按条件查出实体分页（不装配展示字段）。
     *
     * <p>暴露给搜索引擎复用同一套动态条件构造逻辑，避免两处维护筛选规则。</p>
     */
    @Transactional(readOnly = true)
    public Page<KnowledgeItem> findItems(KnowledgeDTO.ItemQuery q, boolean includeUnpublished) {
        Specification<KnowledgeItem> spec = buildSpec(q, includeUnpublished);
        Pageable pageable = PageRequest.of(Math.max(q.getPage() - 1, 0),
                Math.min(Math.max(q.getSize(), 1), 100), buildSort(q));
        return itemRepository.findAll(spec, pageable);
    }

    /** 批量装配展示字段（供搜索引擎在拿到实体列表后调用）。 */
    @Transactional(readOnly = true)
    public List<KnowledgeDTO.ItemDTO> enrichList(List<KnowledgeItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<KnowledgeDTO.ItemDTO> dtos = items.stream()
                .map(KnowledgeDTO.ItemDTO::from)
                .collect(Collectors.toList());
        return enrichAll(dtos, items);
    }

    /** 当前用户创建的内容。 */
    @Transactional(readOnly = true)
    public PageResult<KnowledgeDTO.ItemDTO> mine(KnowledgeDTO.ItemQuery q) {
        q.setAuthorId(SecurityUtils.currentUserId());
        return page(q, true);
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeDTO.ItemDTO> archived(String keyword, int page, int size) {
        return PageResult.of(
                itemRepository.findArchived(keyword == null ? "" : keyword,
                        PageRequest.of(Math.max(page - 1, 0), size)),
                i -> enrich(KnowledgeDTO.ItemDTO.from(i), i));
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDTO.ItemDTO> hot(int limit) {
        return itemRepository.findHot(PageRequest.of(0, limit)).stream()
                .map(i -> enrich(KnowledgeDTO.ItemDTO.from(i), i))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDTO.ItemDTO> recent(int limit) {
        return itemRepository.findRecent(PageRequest.of(0, limit)).stream()
                .map(i -> enrich(KnowledgeDTO.ItemDTO.from(i), i))
                .toList();
    }

    /** 按本体概念查询关联的知识条目。 */
    @Transactional(readOnly = true)
    public List<KnowledgeDTO.ItemDTO> byConcepts(List<Long> conceptIds, int limit) {
        if (conceptIds == null || conceptIds.isEmpty()) {
            return List.of();
        }
        List<Long> itemIds = conceptLinkRepository.findItemIdsByConceptIds(conceptIds);
        if (itemIds.isEmpty()) {
            return List.of();
        }
        return itemRepository.findByIdInAndDeleted(itemIds, 0).stream()
                .filter(i -> KnowledgeItem.PUBLISHED.equals(i.getStatus()))
                .sorted(Comparator.comparing(KnowledgeItem::getViewCount,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .map(i -> enrich(KnowledgeDTO.ItemDTO.from(i), i))
                .toList();
    }

    /** 全文索引/向量索引所需的数据源。 */
    @Transactional(readOnly = true)
    public List<KnowledgeItem> findIndexable() {
        return itemRepository.findAll().stream()
                .filter(i -> i.getDeleted() == null || i.getDeleted() == 0)
                .filter(i -> !KnowledgeItem.ARCHIVED.equals(i.getStatus()))
                .toList();
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private void applyFields(KnowledgeItem item, KnowledgeDTO.ItemSaveRequest req) {
        item.setTitle(req.getTitle().trim());
        item.setSummary(StringUtils.hasText(req.getSummary())
                ? req.getSummary()
                : TextUtils.abbreviate(TextUtils.markdownToPlain(req.getContent()), 200));
        item.setContent(req.getContent());
        item.setContentFormat(StringUtils.hasText(req.getContentFormat()) ? req.getContentFormat() : "MARKDOWN");
        item.setAssetType(StringUtils.hasText(req.getAssetType()) ? req.getAssetType() : "DOCUMENT");
        item.setCategoryId(req.getCategoryId());
        item.setSourceUrl(req.getSourceUrl());
        item.setSourceDesc(req.getSourceDesc());
        item.setCoverImage(req.getCoverImage());
        item.setUpdatedAt(LocalDateTime.now());
    }

    /** 正文 + 附件抽取文本 = 参与检索的纯文本。 */
    private String buildPlainText(KnowledgeItem item, KnowledgeDTO.ItemSaveRequest req) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(req.getContent())) {
            sb.append("MARKDOWN".equalsIgnoreCase(item.getContentFormat())
                    ? TextUtils.markdownToPlain(req.getContent())
                    : req.getContent()).append('\n');
        }
        if (StringUtils.hasText(req.getSummary())) {
            sb.append(req.getSummary()).append('\n');
        }
        return sb.toString().trim();
    }

    /** 附件正文变化后刷新条目的纯文本。 */
    private void refreshPlainTextWithAttachments(KnowledgeItem item) {
        List<Attachment> attachments = attachmentRepository.findWithTextByItem(item.getId());
        if (attachments.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(item.getPlainText() == null ? "" : item.getPlainText());
        for (Attachment a : attachments) {
            if (StringUtils.hasText(a.getExtractedText())) {
                sb.append("\n\n【附件：").append(a.getFileName()).append("】\n")
                        .append(a.getExtractedText());
            }
        }
        String plain = sb.toString();
        // MySQL 全文索引对 LONGTEXT 无硬上限，但过长的正文检索价值递减，做一次上限保护
        int max = 500_000;
        if (plain.length() > max) {
            plain = plain.substring(0, max);
        }
        item.setPlainText(plain);
        itemRepository.save(item);
    }

    private void replaceTags(Long itemId, List<String> tagNames) {
        tagLinkRepository.deleteByItemId(itemId);
        if (tagNames == null || tagNames.isEmpty()) {
            return;
        }
        List<ItemTagLink> links = new ArrayList<>();
        for (String raw : new LinkedHashSet<>(tagNames)) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty() || name.length() > 64) {
                continue;
            }
            Tag tag = tagRepository.findByName(name).orElseGet(() -> {
                Tag t = new Tag();
                t.setName(name);
                t.setUseCount(0);
                t.setCreatedAt(LocalDateTime.now());
                return tagRepository.save(t);
            });
            links.add(new ItemTagLink(itemId, tag.getId()));
        }
        tagLinkRepository.saveAll(links);

        // 重算标签使用次数（比增量维护更不容易漂移）
        Map<Long, Integer> usage = new HashMap<>();
        for (Object[] row : tagLinkRepository.countGroupByTag()) {
            usage.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        tagRepository.findAll().forEach(t -> {
            int c = usage.getOrDefault(t.getId(), 0);
            if (!Objects.equals(t.getUseCount(), c)) {
                t.setUseCount(c);
                tagRepository.save(t);
            }
        });
    }

    /**
     * 重建条目的本体概念标注。
     *
     * @param manualIds   人工指定的概念 id，优先级最高，始终保留
     * @param autoEnabled 是否启用基于别名的自动标注
     */
    private void replaceConcepts(Long itemId, List<Long> manualIds, Boolean autoEnabled) {
        conceptLinkRepository.deleteByItemId(itemId);
        Map<Long, ItemConceptLink> links = new LinkedHashMap<>();

        if (manualIds != null) {
            for (Long cid : manualIds) {
                if (cid != null) {
                    links.put(cid, link(itemId, cid, "MANUAL", 1.0));
                }
            }
        }

        if (autoEnabled == null || autoEnabled) {
            KnowledgeItem item = itemRepository.findById(itemId).orElse(null);
            if (item != null) {
                String text = (item.getTitle() == null ? "" : item.getTitle()) + "\n"
                        + (item.getPlainText() == null ? "" : item.getPlainText());
                for (cn.edu.njupt.kms.module.ontology.dto.OntologyDTO.ConceptDTO c
                        : ontologyService.link(text, 15)) {
                    links.putIfAbsent(c.getId(), link(itemId, c.getId(), "AUTO", 0.7));
                }
            }
        }

        if (!links.isEmpty()) {
            conceptLinkRepository.saveAll(links.values());
        }
    }

    private ItemConceptLink link(Long itemId, Long conceptId, String source, double confidence) {
        ItemConceptLink l = new ItemConceptLink();
        l.setItemId(itemId);
        l.setConceptId(conceptId);
        l.setSource(source);
        l.setConfidence(java.math.BigDecimal.valueOf(confidence));
        return l;
    }

    private void bindAttachments(Long itemId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        attachmentRepository.bindToItem(itemId, attachmentIds);
    }

    /** 单条详情：填充分类名、标签、概念、附件等展示字段。 */
    private KnowledgeDTO.ItemDTO enrich(KnowledgeDTO.ItemDTO dto, KnowledgeItem item) {
        return enrichAll(List.of(dto), List.of(item)).get(0);
    }

    /**
     * 批量填充展示字段。
     *
     * <p>标签、概念、附件、分类、作者都按 id 集合一次性查库后在内存里分发，
     * 避免列表页对每条记录各查一次造成的 N+1 问题。</p>
     */
    private List<KnowledgeDTO.ItemDTO> enrichAll(List<KnowledgeDTO.ItemDTO> dtos,
                                                 List<KnowledgeItem> items) {
        if (dtos.isEmpty()) {
            return dtos;
        }
        List<Long> itemIds = items.stream().map(KnowledgeItem::getId).toList();

        // 分类
        Set<Long> categoryIds = items.stream().map(KnowledgeItem::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Category> categories = categoryIds.isEmpty() ? Map.of()
                : categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        // 作者
        Set<Long> authorIds = items.stream().map(KnowledgeItem::getAuthorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> authors = authorIds.isEmpty() ? Map.of()
                : userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // 标签
        Map<Long, List<ItemTagLink>> tagsByItem = tagLinkRepository.findByItemIdIn(itemIds).stream()
                .collect(Collectors.groupingBy(ItemTagLink::getItemId));
        Set<Long> tagIds = tagsByItem.values().stream()
                .flatMap(List::stream).map(ItemTagLink::getTagId).collect(Collectors.toSet());
        Map<Long, Tag> tags = tagIds.isEmpty() ? Map.of()
                : tagRepository.findAllById(tagIds).stream().collect(Collectors.toMap(Tag::getId, t -> t));

        // 概念
        Map<Long, List<ItemConceptLink>> conceptsByItem = conceptLinkRepository.findByItemIdIn(itemIds).stream()
                .collect(Collectors.groupingBy(ItemConceptLink::getItemId));
        Set<Long> conceptIds = conceptsByItem.values().stream()
                .flatMap(List::stream).map(ItemConceptLink::getConceptId).collect(Collectors.toSet());
        Map<Long, Concept> concepts = conceptIds.isEmpty() ? Map.of()
                : conceptRepository.findAllById(conceptIds).stream()
                .collect(Collectors.toMap(Concept::getId, c -> c));

        // 附件
        Map<Long, List<Attachment>> attachmentsByItem = attachmentRepository.findByItemIdIn(itemIds).stream()
                .collect(Collectors.groupingBy(Attachment::getItemId));

        for (int i = 0; i < dtos.size(); i++) {
            KnowledgeDTO.ItemDTO dto = dtos.get(i);
            KnowledgeItem item = items.get(i);
            Long id = item.getId();

            Category cat = item.getCategoryId() == null ? null : categories.get(item.getCategoryId());
            if (cat != null) {
                dto.setCategoryName(cat.getName());
                dto.setCategoryPath(cat.getPath());
            }

            User author = item.getAuthorId() == null ? null : authors.get(item.getAuthorId());
            if (author != null) {
                dto.setAuthorName(StringUtils.hasText(author.getRealName())
                        ? author.getRealName() : author.getUsername());
            }

            List<ItemTagLink> tagLinks = tagsByItem.getOrDefault(id, List.of());
            dto.setTags(tagLinks.stream()
                    .map(l -> tags.get(l.getTagId()))
                    .filter(Objects::nonNull)
                    .map(KnowledgeDTO.TagDTO::from)
                    .toList());

            List<ItemConceptLink> conceptLinks = conceptsByItem.getOrDefault(id, List.of());
            dto.setConceptIds(conceptLinks.stream().map(ItemConceptLink::getConceptId).toList());
            dto.setConceptNames(conceptLinks.stream()
                    .map(l -> concepts.get(l.getConceptId()))
                    .filter(Objects::nonNull)
                    .map(Concept::getName)
                    .toList());

            dto.setAttachments(attachmentsByItem.getOrDefault(id, List.of()).stream()
                    .map(a -> KnowledgeDTO.AttachmentDTO.from(a, "/api/files/preview"))
                    .toList());

            dto.setSnapshotDue(policyService.isSnapshotDue(item));
        }
        return dtos;
    }

    /**
     * 动态查询条件。
     *
     * <p>标签与概念是多值条件：先用关联表查出候选条目 id，再作为 {@code IN} 条件拼入，
     * 避免在多表 join 下写复杂的分页 SQL。</p>
     */
    private Specification<KnowledgeItem> buildSpec(KnowledgeDTO.ItemQuery q, boolean includeUnpublished) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("deleted"), 0));

            // 状态可见性
            if (StringUtils.hasText(q.getStatus())) {
                ps.add(cb.equal(root.get("status"), q.getStatus()));
            } else if (!includeUnpublished) {
                ps.add(cb.equal(root.get("status"), KnowledgeItem.PUBLISHED));
            } else {
                ps.add(cb.notEqual(root.get("status"), KnowledgeItem.ARCHIVED));
            }

            if (StringUtils.hasText(q.getKeyword())) {
                String kw = "%" + q.getKeyword().trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.<String>get("title")), kw),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("summary"), "")), kw),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("keywords"), "")), kw)));
            }

            if (q.getCategoryId() != null) {
                List<Long> catIds = categoryService.selfAndDescendantIds(q.getCategoryId());
                ps.add(catIds.isEmpty() ? cb.disjunction() : root.get("categoryId").in(catIds));
            }

            if (q.getAssetTypes() != null && !q.getAssetTypes().isEmpty()) {
                ps.add(root.get("assetType").in(q.getAssetTypes()));
            }

            if (q.getAuthorId() != null) {
                ps.add(cb.equal(root.get("authorId"), q.getAuthorId()));
            }

            if (q.getCreatedFrom() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), q.getCreatedFrom()));
            }
            if (q.getCreatedTo() != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), q.getCreatedTo()));
            }

            if (q.getTagIds() != null && !q.getTagIds().isEmpty()) {
                List<Long> ids = tagLinkRepository.findItemIdsByTagIds(q.getTagIds());
                ps.add(ids.isEmpty() ? cb.disjunction() : root.get("id").in(ids));
            }

            if (q.getConceptIds() != null && !q.getConceptIds().isEmpty()) {
                List<Long> ids = conceptLinkRepository.findItemIdsByConceptIds(q.getConceptIds());
                ps.add(ids.isEmpty() ? cb.disjunction() : root.get("id").in(ids));
            }

            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Sort buildSort(KnowledgeDTO.ItemQuery q) {
        String field = switch (q.getSortBy() == null ? "updatedAt" : q.getSortBy()) {
            case "createdAt", "viewCount", "likeCount", "publishedAt", "title", "updatedAt" -> q.getSortBy();
            default -> "updatedAt";
        };
        Sort.Direction dir = "asc".equalsIgnoreCase(q.getSortDir()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }

    public KnowledgeItem require(Long id) {
        KnowledgeItem item = itemRepository.findById(id).orElseThrow(() -> BizException.notFound("知识条目"));
        if (item.getDeleted() != null && item.getDeleted() == 1) {
            throw BizException.notFound("知识条目");
        }
        return item;
    }
}
