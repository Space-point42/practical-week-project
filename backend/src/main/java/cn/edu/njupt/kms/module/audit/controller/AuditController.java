package cn.edu.njupt.kms.module.audit.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.common.PageResult;
import cn.edu.njupt.kms.module.audit.entity.AuditLog;
import cn.edu.njupt.kms.module.audit.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** 审计日志接口（仅管理员）。 */
@Tag(name = "12 审计日志", description = "关键操作的可追溯记录")
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditService auditService;

    @Operation(summary = "分页查询审计日志")
    @GetMapping
    public ApiResponse<PageResult<AuditLog>> page(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.page(action, targetType, username, from, to, page, size));
    }

    @Operation(summary = "最新审计记录")
    @GetMapping("/recent")
    public ApiResponse<List<AuditLog>> recent() {
        return ApiResponse.ok(auditService.recent());
    }

    @Operation(summary = "某对象的变更历史")
    @GetMapping("/target")
    public ApiResponse<List<AuditLog>> ofTarget(@RequestParam String targetType,
                                                @RequestParam Long targetId) {
        return ApiResponse.ok(auditService.ofTarget(targetType, targetId));
    }
}
