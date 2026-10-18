package cn.edu.njupt.kms.module.cms.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 版本管理策略 —— 实现题目“需提供指定天数/修改次数的版本管理”的要求。
 *
 * <p>触发条件（满足其一即建立新版本快照）：</p>
 * <ol>
 *   <li><b>天数</b>：距上次快照已过 {@link #intervalDays} 天；</li>
 *   <li><b>修改次数</b>：自上次快照以来累计保存 {@link #minChangeCount} 次。</li>
 * </ol>
 *
 * <p>策略支持三级作用域，查找时按 ITEM → CATEGORY → GLOBAL 的顺序取第一条命中的启用策略。
 * {@link #maxVersions} 限制保留的版本总数，超出后淘汰最旧的快照。</p>
 */
@Data
@Entity
@Table(name = "cms_version_policy")
public class VersionPolicy {

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_CATEGORY = "CATEGORY";
    public static final String SCOPE_ITEM = "ITEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String scope = SCOPE_GLOBAL;

    /** scope 对应的分类/条目 id；GLOBAL 时为 null。 */
    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "policy_name", nullable = false, length = 64)
    private String policyName = "默认策略";

    /** 天数阈值。 */
    @Column(name = "interval_days", nullable = false)
    private Integer intervalDays = 1;

    /** 修改次数阈值。 */
    @Column(name = "min_change_count", nullable = false)
    private Integer minChangeCount = 5;

    /** 保留的最大版本数。 */
    @Column(name = "max_versions", nullable = false)
    private Integer maxVersions = 50;

    /** 是否启用自动快照。 */
    @Column(name = "auto_snapshot", nullable = false)
    private Integer autoSnapshot = 1;

    @Column(nullable = false)
    private Integer enabled = 1;

    /** 数值越大优先级越高，用于同作用域下的多策略选择。 */
    @Column(nullable = false)
    private Integer priority = 0;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isEnabled() {
        return enabled != null && enabled == 1;
    }

    public boolean isAutoSnapshotEnabled() {
        return autoSnapshot != null && autoSnapshot == 1;
    }
}
