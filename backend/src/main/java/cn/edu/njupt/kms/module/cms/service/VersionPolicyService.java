package cn.edu.njupt.kms.module.cms.service;

import cn.edu.njupt.kms.common.BizException;
import cn.edu.njupt.kms.config.AppProperties;
import cn.edu.njupt.kms.module.audit.service.AuditService;
import cn.edu.njupt.kms.module.cms.dto.CmsDTO;
import cn.edu.njupt.kms.module.cms.entity.VersionPolicy;
import cn.edu.njupt.kms.module.cms.repository.VersionPolicyRepository;
import cn.edu.njupt.kms.module.kb.entity.Category;
import cn.edu.njupt.kms.module.kb.entity.KnowledgeItem;
import cn.edu.njupt.kms.module.kb.repository.CategoryRepository;
import cn.edu.njupt.kms.module.kb.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 版本策略服务：决定「什么时候该为条目建立一个新的版本快照」。
 *
 * <p>策略解析顺序为 ITEM（条目级）→ CATEGORY（分类级）→ GLOBAL（全局），
 * 未配置任何策略时使用 {@code kms.version.*} 中的默认值，
 * 保证系统开箱即用、且每个层级都可被覆盖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionPolicyService {

    private final VersionPolicyRepository policyRepository;
    private final KnowledgeItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final AppProperties props;
    private final AuditService auditService;

    /** 首次启动时确保存在一条全局默认策略。 */
    @Transactional
    public void ensureDefaultPolicy() {
        if (!policyRepository.findByScopeAndEnabledOrderByPriorityDesc(
                VersionPolicy.SCOPE_GLOBAL, 1).isEmpty()) {
            return;
        }
        VersionPolicy p = new VersionPolicy();
        p.setScope(VersionPolicy.SCOPE_GLOBAL);
        p.setScopeId(null);
        p.setPolicyName("默认全局策略");
        p.setIntervalDays(props.getVersion().getDefaultIntervalDays());
        p.setMinChangeCount(props.getVersion().getDefaultMinChangeCount());
        p.setMaxVersions(props.getVersion().getDefaultMaxVersions());
        p.setAutoSnapshot(props.getVersion().isAutoSnapshotEnabled() ? 1 : 0);
        p.setEnabled(1);
        p.setPriority(0);
        p.setUpdatedAt(LocalDateTime.now());
        policyRepository.save(p);
        log.info("已创建默认版本策略：天数阈值={} 天，修改次数阈值={} 次",
                p.getIntervalDays(), p.getMinChangeCount());
    }

    // ------------------------------------------------------------------
    // 策略查询
    // ------------------------------------------------------------------

    /** 解析某条目实际生效的策略（ITEM → CATEGORY → GLOBAL → 代码默认值）。 */
    @Transactional(readOnly = true)
    public VersionPolicy resolve(Long itemId, Long categoryId) {
        if (itemId != null) {
            Optional<VersionPolicy> itemPolicy =
                    policyRepository.findByScopeAndScopeIdAndEnabledOrderByPriorityDesc(
                            VersionPolicy.SCOPE_ITEM, itemId, 1).stream().findFirst();
            if (itemPolicy.isPresent()) {
                return itemPolicy.get();
            }
        }
        if (categoryId != null) {
            Optional<VersionPolicy> catPolicy =
                    policyRepository.findByScopeAndScopeIdAndEnabledOrderByPriorityDesc(
                            VersionPolicy.SCOPE_CATEGORY, categoryId, 1).stream().findFirst();
            if (catPolicy.isPresent()) {
                return catPolicy.get();
            }
        }
        return policyRepository.findByScopeAndEnabledOrderByPriorityDesc(
                        VersionPolicy.SCOPE_GLOBAL, 1).stream().findFirst()
                .orElseGet(this::fallbackPolicy);
    }

    /** 代码级兜底策略（数据库中无任何配置时使用）。 */
    private VersionPolicy fallbackPolicy() {
        VersionPolicy p = new VersionPolicy();
        p.setId(null);
        p.setPolicyName("内置默认策略");
        p.setScope(VersionPolicy.SCOPE_GLOBAL);
        p.setIntervalDays(props.getVersion().getDefaultIntervalDays());
        p.setMinChangeCount(props.getVersion().getDefaultMinChangeCount());
        p.setMaxVersions(props.getVersion().getDefaultMaxVersions());
        p.setAutoSnapshot(props.getVersion().isAutoSnapshotEnabled() ? 1 : 0);
        p.setEnabled(1);
        return p;
    }

    // ------------------------------------------------------------------
    // 判定
    // ------------------------------------------------------------------

    /**
     * 判定某条目当前是否满足建立新版本快照的条件。
     *
     * <p>条件：距上次快照天数 ≥ 阈值 <b>或</b> 累计修改次数 ≥ 阈值。</p>
     */
    @Transactional(readOnly = true)
    public CmsDTO.SnapshotDecisionDTO evaluate(KnowledgeItem item) {
        VersionPolicy policy = resolve(item.getId(), item.getCategoryId());
        return evaluate(item, policy);
    }

    public CmsDTO.SnapshotDecisionDTO evaluate(KnowledgeItem item, VersionPolicy policy) {
        CmsDTO.SnapshotDecisionDTO d = new CmsDTO.SnapshotDecisionDTO();
        d.setPolicyId(policy.getId());
        d.setPolicyName(policy.getPolicyName());
        d.setIntervalDays(policy.getIntervalDays());
        d.setMinChangeCount(policy.getMinChangeCount());

        long days;
        LocalDateTime last = item.getLastSnapshotAt() != null ? item.getLastSnapshotAt() : item.getCreatedAt();
        if (last == null) {
            days = Long.MAX_VALUE;
        } else {
            days = Duration.between(last, LocalDateTime.now()).toDays();
        }
        d.setDaysSinceLastSnapshot(days);
        d.setPendingChangeCount(item.getChangeCount() == null ? 0 : item.getChangeCount());

        if (!policy.isEnabled() || !policy.isAutoSnapshotEnabled()) {
            d.setDue(false);
            d.setReason("该条目适用策略「%s」，但自动快照已关闭，仅在手工创建/发布时生成版本"
                    .formatted(policy.getPolicyName()));
            return d;
        }

        boolean byDays = days >= policy.getIntervalDays();
        boolean byCount = d.getPendingChangeCount() >= policy.getMinChangeCount();

        if (byDays && byCount) {
            d.setDue(true);
            d.setReason("距上次快照 %d 天（≥ %d 天）且累计修改 %d 次（≥ %d 次），满足建版条件"
                    .formatted(days, policy.getIntervalDays(), d.getPendingChangeCount(), policy.getMinChangeCount()));
        } else if (byDays) {
            d.setDue(true);
            d.setReason("距上次快照 %d 天，已达到 %d 天的天数阈值"
                    .formatted(days, policy.getIntervalDays()));
        } else if (byCount) {
            d.setDue(true);
            d.setReason("累计修改 %d 次，已达到 %d 次的次数阈值"
                    .formatted(d.getPendingChangeCount(), policy.getMinChangeCount()));
        } else {
            d.setDue(false);
            d.setReason("距上次快照 %d 天（阈值 %d 天），累计修改 %d 次（阈值 %d 次），尚未满足建版条件"
                    .formatted(days, policy.getIntervalDays(), d.getPendingChangeCount(), policy.getMinChangeCount()));
        }
        return d;
    }

    /** 简易判定：仅返回是否到期，供列表页批量标注。 */
    @Transactional(readOnly = true)
    public boolean isSnapshotDue(KnowledgeItem item) {
        try {
            VersionPolicy policy = resolve(item.getId(), item.getCategoryId());
            if (!policy.isEnabled() || !policy.isAutoSnapshotEnabled()) {
                return false;
            }
            LocalDateTime last = item.getLastSnapshotAt() != null ? item.getLastSnapshotAt() : item.getCreatedAt();
            long days = last == null ? Long.MAX_VALUE : Duration.between(last, LocalDateTime.now()).toDays();
            int changes = item.getChangeCount() == null ? 0 : item.getChangeCount();
            return days >= policy.getIntervalDays() || changes >= policy.getMinChangeCount();
        } catch (Exception e) {
            log.debug("版本策略判定失败，按“不需建版”处理：{}", e.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 策略维护
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CmsDTO.VersionPolicyDTO> list() {
        return policyRepository.findAllByOrderByScopeAscPriorityDescIdAsc().stream()
                .map(p -> {
                    CmsDTO.VersionPolicyDTO dto = CmsDTO.VersionPolicyDTO.from(p);
                    dto.setTargetName(resolveTargetName(p));
                    return dto;
                })
                .toList();
    }

    @Transactional
    public CmsDTO.VersionPolicyDTO create(CmsDTO.VersionPolicySaveRequest req) {
        validateScope(req);
        VersionPolicy p = new VersionPolicy();
        apply(p, req);
        p.setUpdatedAt(LocalDateTime.now());
        policyRepository.save(p);
        auditService.record("CREATE", "POLICY", p.getId(), "新增版本策略：" + p.getPolicyName());
        return CmsDTO.VersionPolicyDTO.from(p);
    }

    @Transactional
    public CmsDTO.VersionPolicyDTO update(Long id, CmsDTO.VersionPolicySaveRequest req) {
        VersionPolicy p = policyRepository.findById(id).orElseThrow(() -> BizException.notFound("版本策略"));
        validateScope(req);
        apply(p, req);
        p.setUpdatedAt(LocalDateTime.now());
        policyRepository.save(p);
        auditService.record("UPDATE", "POLICY", id, "更新版本策略：" + p.getPolicyName());
        return CmsDTO.VersionPolicyDTO.from(p);
    }

    @Transactional
    public void delete(Long id) {
        VersionPolicy p = policyRepository.findById(id).orElseThrow(() -> BizException.notFound("版本策略"));
        if (VersionPolicy.SCOPE_GLOBAL.equals(p.getScope())
                && policyRepository.findByScopeAndEnabledOrderByPriorityDesc(VersionPolicy.SCOPE_GLOBAL, 1).size() <= 1) {
            throw BizException.badRequest("必须保留至少一条全局策略，否则系统将使用内置默认值");
        }
        policyRepository.delete(p);
        auditService.record("DELETE", "POLICY", id, "删除版本策略：" + p.getPolicyName());
    }

    private void validateScope(CmsDTO.VersionPolicySaveRequest req) {
        if (!VersionPolicy.SCOPE_GLOBAL.equals(req.getScope()) && req.getScopeId() == null) {
            throw BizException.badRequest("非全局策略必须指定作用对象（分类或条目）");
        }
    }

    private void apply(VersionPolicy p, CmsDTO.VersionPolicySaveRequest req) {
        p.setPolicyName(req.getPolicyName());
        p.setScope(req.getScope());
        p.setScopeId(VersionPolicy.SCOPE_GLOBAL.equals(req.getScope()) ? null : req.getScopeId());
        p.setIntervalDays(req.getIntervalDays() == null ? 1 : req.getIntervalDays());
        p.setMinChangeCount(req.getMinChangeCount() == null ? 5 : req.getMinChangeCount());
        p.setMaxVersions(req.getMaxVersions() == null ? 50 : req.getMaxVersions());
        p.setAutoSnapshot(req.getAutoSnapshot() == null ? 1 : req.getAutoSnapshot());
        p.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
        p.setPriority(req.getPriority() == null ? 0 : req.getPriority());
    }

    private String resolveTargetName(VersionPolicy p) {
        if (p.getScopeId() == null) {
            return "全部知识条目";
        }
        return switch (p.getScope()) {
            case VersionPolicy.SCOPE_CATEGORY -> categoryRepository.findById(p.getScopeId())
                    .map(Category::getName).orElse("分类#" + p.getScopeId());
            case VersionPolicy.SCOPE_ITEM -> itemRepository.findById(p.getScopeId())
                    .map(KnowledgeItem::getTitle).orElse("条目#" + p.getScopeId());
            default -> "全部知识条目";
        };
    }
}
