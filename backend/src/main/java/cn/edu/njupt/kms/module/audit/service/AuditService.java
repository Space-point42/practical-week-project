package cn.edu.njupt.kms.module.audit.service;

import cn.edu.njupt.kms.common.PageResult;
import cn.edu.njupt.kms.module.audit.entity.AuditLog;
import cn.edu.njupt.kms.module.audit.repository.AuditLogRepository;
import cn.edu.njupt.kms.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计服务。
 *
 * <p>记录采用独立事务（{@code REQUIRES_NEW}）且异步执行，
 * 保证审计失败不会影响主业务，也不会因主事务回滚而丢失痕迹。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;

    /** 记录一条审计日志（自动填充当前用户与 IP）。 */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String targetType, Long targetId, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setUserId(SecurityUtils.currentUserId());
            entry.setUsername(SecurityUtils.currentUsername());
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setDetail(detail == null ? null : truncate(detail, 1000));
            entry.setIp(currentIp());
            entry.setCreatedAt(LocalDateTime.now());
            repository.save(entry);
        } catch (Exception e) {
            // 审计失败不应影响业务
            log.warn("写入审计日志失败：action={} target={}#{}", action, targetType, targetId, e);
        }
    }

    /** 同步记录：用于需要立即落库的场景（如事务内强一致审计）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSync(String action, String targetType, Long targetId, String detail) {
        AuditLog entry = new AuditLog();
        entry.setUserId(SecurityUtils.currentUserId());
        entry.setUsername(SecurityUtils.currentUsername());
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setDetail(detail);
        entry.setIp(currentIp());
        entry.setCreatedAt(LocalDateTime.now());
        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public PageResult<AuditLog> page(String action, String targetType, String username,
                                     LocalDateTime from, LocalDateTime to, int page, int size) {
        return PageResult.of(
                repository.search(action, targetType, username, from, to, PageRequest.of(Math.max(page - 1, 0), size)),
                a -> a);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> recent() {
        return repository.findTop20ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<AuditLog> ofTarget(String targetType, Long targetId) {
        return repository.findByTargetTypeAndTargetIdOrderByIdDesc(targetType, targetId);
    }

    // ------------------------------------------------------------------

    private String currentIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
