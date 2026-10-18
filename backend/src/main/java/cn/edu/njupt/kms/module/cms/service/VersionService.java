package cn.edu.njupt.kms.module.cms.service;

import cn.edu.njupt.kms.common.BizException;
import cn.edu.njupt.kms.common.PageResult;
import cn.edu.njupt.kms.common.event.ItemChangedEvent;
import cn.edu.njupt.kms.module.audit.service.AuditService;
import cn.edu.njupt.kms.module.cms.dto.CmsDTO;
import cn.edu.njupt.kms.module.cms.entity.ItemVersion;
import cn.edu.njupt.kms.module.cms.entity.VersionPolicy;
import cn.edu.njupt.kms.module.cms.repository.ItemVersionRepository;
import cn.edu.njupt.kms.module.kb.entity.ItemConceptLink;
import cn.edu.njupt.kms.module.kb.entity.ItemTagLink;
import cn.edu.njupt.kms.module.kb.entity.KnowledgeItem;
import cn.edu.njupt.kms.module.kb.entity.Tag;
import cn.edu.njupt.kms.module.kb.repository.ItemConceptLinkRepository;
import cn.edu.njupt.kms.module.kb.repository.ItemTagLinkRepository;
import cn.edu.njupt.kms.module.kb.repository.KnowledgeItemRepository;
import cn.edu.njupt.kms.module.kb.repository.TagRepository;
import cn.edu.njupt.kms.module.ontology.repository.ConceptRepository;
import cn.edu.njupt.kms.security.SecurityUtils;
import cn.edu.njupt.kms.util.TextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 版本管理服务：快照的创建、查询、比对与回滚。
 *
 * <p>何时建版由 {@link VersionPolicyService} 依据「天数 / 修改次数」策略决定；
 * 本服务负责具体的快照落库、版本号推进与超量淘汰。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionService {

    private final ItemVersionRepository versionRepository;
    private final KnowledgeItemRepository itemRepository;
    private final ItemConceptLinkRepository conceptLinkRepository;
    private final ItemTagLinkRepository tagLinkRepository;
    private final ConceptRepository conceptRepository;
    private final TagRepository tagRepository;
    private final VersionPolicyService policyService;
    private final DiffService diffService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    // ==================================================================
    // 快照创建
    // ==================================================================

    /**
     * 按策略自动建版：仅当策略判定到期时才生成快照。
     *
     * @return 若生成了新版本则返回该版本，否则返回 {@code null}
     */
    @Transactional
    public ItemVersion autoSnapshot(KnowledgeItem item, String changeLog) {
        CmsDTO.SnapshotDecisionDTO decision = policyService.evaluate(item);
        if (!decision.isDue()) {
            log.debug("条目 #{} 未满足建版条件：{}", item.getId(), decision.getReason());
            return null;
        }
        VersionPolicy policy = policyService.resolve(item.getId(), item.getCategoryId());
        String log = StringUtils.hasText(changeLog) ? changeLog
                : "自动快照（%s）".formatted(decision.getReason());
        return doSnapshot(item, ItemVersion.SOURCE_AUTO, log, policy);
    }

    /** 无条件建版（用于发布、手工创建等显式操作）。 */
    @Transactional
    public ItemVersion snapshot(KnowledgeItem item, String source, String changeLog) {
        VersionPolicy policy = policyService.resolve(item.getId(), item.getCategoryId());
        return doSnapshot(item, source, changeLog, policy);
    }

    private ItemVersion doSnapshot(KnowledgeItem item, String source, String changeLog, VersionPolicy policy) {
        String newContent = item.getContent() == null ? "" : item.getContent();
        String newHash = TextUtils.md5(newContent);

        // 与上一版本内容完全一致时不重复建版（显式发布除外）
        ItemVersion last = versionRepository.findFirstByItemIdOrderByVersionNoDesc(item.getId()).orElse(null);
        if (last != null && newHash.equals(last.getContentHash())
                && !ItemVersion.SOURCE_PUBLISH.equals(source)) {
            log.debug("条目 #{} 正文未变化，跳过建版", item.getId());
            return null;
        }

        int nextNo = versionRepository.maxVersionNo(item.getId()) + 1;
        ItemVersion v = new ItemVersion();
        v.setItemId(item.getId());
        v.setVersionNo(nextNo);
        v.setTitle(item.getTitle());
        v.setSummary(item.getSummary());
        v.setContent(item.getContent());
        v.setCategoryId(item.getCategoryId());
        v.setStatus(item.getStatus());
        v.setAssetType(item.getAssetType());
        v.setConceptsSnapshot(conceptsSnapshot(item.getId()));
        v.setTagsSnapshot(tagsSnapshot(item.getId()));
        v.setChangeLog(changeLog);
        v.setChangeSource(source);
        v.setContentHash(newHash);
        v.setEditorId(SecurityUtils.currentUserId());
        v.setEditorName(SecurityUtils.currentUsername());
        v.setCreatedAt(LocalDateTime.now());

        if (last != null) {
            int[] delta = TextUtils.charDelta(last.getContent(), item.getContent());
            v.setCharAdded(delta[0]);
            v.setCharRemoved(delta[1]);
        } else {
            v.setCharAdded(newContent.length());
            v.setCharRemoved(0);
        }

        versionRepository.save(v);

        // 推进条目自身的版本号，并重置「距上次快照的修改次数」
        item.setVersionNo(nextNo);
        item.setLastSnapshotAt(LocalDateTime.now());
        item.setChangeCount(0);
        itemRepository.save(item);

        pruneIfNeeded(item.getId(), policy.getMaxVersions());

        log.info("条目 #{} 生成版本 v{}（来源 {}）", item.getId(), nextNo, source);
        return v;
    }

    /** 手工创建版本（需填写版本说明）。 */
    @Transactional
    public CmsDTO.VersionDTO createManual(Long itemId, CmsDTO.VersionCreateRequest req) {
        KnowledgeItem item = requireItem(itemId);
        ItemVersion v = snapshot(item, ItemVersion.SOURCE_MANUAL, req.getChangeLog());
        if (v == null) {
            throw BizException.badRequest("当前正文与最新版本完全一致，无需重复创建版本");
        }
        auditService.record("CREATE_VERSION", "ITEM", itemId, "手工创建版本 v" + v.getVersionNo());
        return CmsDTO.VersionDTO.from(v, false);
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Transactional(readOnly = true)
    public PageResult<CmsDTO.VersionDTO> list(Long itemId, int page, int size) {
        requireItem(itemId);
        int current = itemRepository.findById(itemId).map(KnowledgeItem::getVersionNo).orElse(0);
        return PageResult.of(
                versionRepository.findByItemIdOrderByVersionNoDesc(itemId,
                        PageRequest.of(Math.max(page - 1, 0), size)),
                v -> {
                    CmsDTO.VersionDTO dto = CmsDTO.VersionDTO.from(v, false);
                    dto.setCurrent(v.getVersionNo() != null && v.getVersionNo().equals(current));
                    return dto;
                });
    }

    @Transactional(readOnly = true)
    public CmsDTO.VersionDTO get(Long itemId, Integer versionNo) {
        ItemVersion v = versionRepository.findByItemIdAndVersionNo(itemId, versionNo)
                .orElseThrow(() -> BizException.notFound("版本 v" + versionNo));
        CmsDTO.VersionDTO dto = CmsDTO.VersionDTO.from(v, true);
        int current = itemRepository.findById(itemId).map(KnowledgeItem::getVersionNo).orElse(0);
        dto.setCurrent(versionNo.equals(current));
        return dto;
    }

    /** 计算两个版本的差异；{@code fromVersion} 为空时与「上一版本」比较。 */
    @Transactional(readOnly = true)
    public CmsDTO.VersionDiffDTO diff(Long itemId, Integer fromVersion, Integer toVersion) {
        CmsDTO.VersionDTO to;
        CmsDTO.VersionDTO from;

        if (toVersion == null) {
            ItemVersion latest = versionRepository.findFirstByItemIdOrderByVersionNoDesc(itemId)
                    .orElseThrow(() -> BizException.notFound("该条目尚无任何版本"));
            to = CmsDTO.VersionDTO.from(latest, true);
            int fromNo = fromVersion != null ? fromVersion : latest.getVersionNo() - 1;
            from = get(itemId, fromNo);
        } else {
            to = get(itemId, toVersion);
            int fromNo = fromVersion != null ? fromVersion : toVersion - 1;
            from = get(itemId, fromNo);
        }
        return diffService.diff(from, to);
    }

    /** 工作台：最近变更的版本记录。 */
    @Transactional(readOnly = true)
    public List<CmsDTO.VersionDTO> recentChanges(int limit) {
        return versionRepository.findRecent(PageRequest.of(0, limit)).stream()
                .map(v -> CmsDTO.VersionDTO.from(v, false))
                .toList();
    }

    // ==================================================================
    // 回滚
    // ==================================================================

    /**
     * 回滚到指定版本。
     *
     * <p>回滚不会删除任何历史版本 —— 而是把目标版本的正文写回条目，
     * 并生成一个内容与目标版本一致的新版本（{@code changeSource=ROLLBACK}），
     * 从而保证版本链完整、每一次操作都可追溯。</p>
     *
     * @param itemId    条目 id
     * @param versionNo 目标版本号
     * @param changeLog 回滚说明
     */
    @Transactional
    public CmsDTO.VersionDTO rollback(Long itemId, Integer versionNo, String changeLog) {
        KnowledgeItem item = requireItem(itemId);
        ItemVersion target = versionRepository.findByItemIdAndVersionNo(itemId, versionNo)
                .orElseThrow(() -> BizException.notFound("版本 v" + versionNo));

        if (versionNo.equals(item.getVersionNo())) {
            throw BizException.badRequest("目标版本已是当前版本，无需回滚");
        }

        // 回滚前先为「当前状态」留一份快照，避免回滚动作本身造成内容丢失
        ItemVersion currentSnapshot = doSnapshot(item, ItemVersion.SOURCE_MANUAL,
                "回滚至 v%d 前的自动留存".formatted(versionNo),
                policyService.resolve(itemId, item.getCategoryId()));

        item.setTitle(target.getTitle());
        item.setSummary(target.getSummary());
        item.setContent(target.getContent());
        item.setCategoryId(target.getCategoryId());
        item.setAssetType(target.getAssetType() == null ? item.getAssetType() : target.getAssetType());
        item.setChangeCount(item.getChangeCount() == null ? 1 : item.getChangeCount() + 1);
        itemRepository.save(item);

        String log = StringUtils.hasText(changeLog)
                ? changeLog
                : "回滚至版本 v%d".formatted(versionNo);
        ItemVersion rollbackVersion = snapshot(item, ItemVersion.SOURCE_ROLLBACK,
                log + "（回滚前状态已留存为 v%d）".formatted(
                        currentSnapshot == null ? item.getVersionNo() : currentSnapshot.getVersionNo()));

        auditService.record("ROLLBACK", "ITEM", itemId,
                "回滚条目至 v%d，生成 v%d".formatted(versionNo,
                        rollbackVersion == null ? -1 : rollbackVersion.getVersionNo()));

        // 正文已变，通知 RAG 模块重建索引
        eventPublisher.publishEvent(ItemChangedEvent.rolledBack(itemId));

        return rollbackVersion == null
                ? CmsDTO.VersionDTO.from(target, false)
                : CmsDTO.VersionDTO.from(rollbackVersion, false);
    }

    // ==================================================================
    // 维护
    // ==================================================================

    /** 按策略淘汰超量的旧版本。 */
    @Transactional
    public int pruneIfNeeded(Long itemId, Integer maxVersions) {
        int keep = maxVersions == null || maxVersions <= 0 ? 50 : maxVersions;
        long count = versionRepository.countByItemId(itemId);
        if (count <= keep) {
            return 0;
        }
        int removed = versionRepository.pruneOldVersions(itemId, keep);
        if (removed > 0) {
            log.info("条目 #{} 版本数 {} 超过上限 {}，已淘汰 {} 条最旧版本", itemId, count, keep, removed);
        }
        return removed;
    }

    /** 手工触发一次全量清理。 */
    @Transactional
    public int pruneAll() {
        int total = 0;
        for (KnowledgeItem item : itemRepository.findAll()) {
            VersionPolicy policy = policyService.resolve(item.getId(), item.getCategoryId());
            total += pruneIfNeeded(item.getId(), policy.getMaxVersions());
        }
        return total;
    }

    // ------------------------------------------------------------------

    private KnowledgeItem requireItem(Long itemId) {
        return itemRepository.findById(itemId).orElseThrow(() -> BizException.notFound("知识条目"));
    }

    /**
     * 生成概念标注快照 {@code id:名称|id:名称}。
     *
     * <p>快照里保存名称而非仅 id，是为了让历史版本即使在概念被重命名后
     * 仍能还原出「当时标注了什么」，版本记录因此是自解释的。</p>
     */
    private String conceptsSnapshot(Long itemId) {
        List<ItemConceptLink> links = conceptLinkRepository.findByItemId(itemId);
        if (links.isEmpty()) {
            return null;
        }
        List<Long> ids = links.stream().map(ItemConceptLink::getConceptId).toList();
        Map<Long, String> names = conceptRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(cn.edu.njupt.kms.module.ontology.entity.Concept::getId,
                        cn.edu.njupt.kms.module.ontology.entity.Concept::getName));
        return links.stream()
                .map(l -> l.getConceptId() + ":" + names.getOrDefault(l.getConceptId(), "概念#" + l.getConceptId()))
                .collect(Collectors.joining("|"));
    }

    /** 生成标签快照（名称，逗号分隔）。 */
    private String tagsSnapshot(Long itemId) {
        List<ItemTagLink> links = tagLinkRepository.findByItemId(itemId);
        if (links.isEmpty()) {
            return null;
        }
        List<Long> ids = links.stream().map(ItemTagLink::getTagId).toList();
        Map<Long, String> names = tagRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getName));
        return links.stream()
                .map(l -> names.getOrDefault(l.getTagId(), "标签#" + l.getTagId()))
                .collect(Collectors.joining(","));
    }
}
