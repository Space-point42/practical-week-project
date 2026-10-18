package cn.edu.njupt.kms.module.cms.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.common.PageResult;
import cn.edu.njupt.kms.module.cms.dto.CmsDTO;
import cn.edu.njupt.kms.module.cms.service.VersionPolicyService;
import cn.edu.njupt.kms.module.cms.service.VersionService;
import cn.edu.njupt.kms.module.kb.repository.KnowledgeItemRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 版本管理接口。
 *
 * <p>查询接口对匿名开放（便于比对历史），写操作要求编辑及以上权限，
 * 策略配置仅管理员可改。</p>
 */
@Tag(name = "05 版本管理", description = "知识条目版本快照、差异比对、回滚与版本策略")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VersionController {

    private final VersionService versionService;
    private final VersionPolicyService policyService;
    private final KnowledgeItemRepository itemRepository;

    // ---------------- 版本 ----------------

    @Operation(summary = "某条目的版本列表")
    @GetMapping("/items/{itemId}/versions")
    public ApiResponse<PageResult<CmsDTO.VersionDTO>> list(@PathVariable Long itemId,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(versionService.list(itemId, page, size));
    }

    @Operation(summary = "版本详情（含正文快照）")
    @GetMapping("/items/{itemId}/versions/{versionNo}")
    public ApiResponse<CmsDTO.VersionDTO> detail(@PathVariable Long itemId, @PathVariable Integer versionNo) {
        return ApiResponse.ok(versionService.get(itemId, versionNo));
    }

    @Operation(summary = "版本差异比对")
    @GetMapping("/items/{itemId}/versions/diff")
    public ApiResponse<CmsDTO.VersionDiffDTO> diff(@PathVariable Long itemId,
                                                   @RequestParam(required = false) Integer from,
                                                   @RequestParam(required = false) Integer to) {
        return ApiResponse.ok(versionService.diff(itemId, from, to));
    }

    @Operation(summary = "手工创建版本")
    @PostMapping("/items/{itemId}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<CmsDTO.VersionDTO> create(@PathVariable Long itemId,
                                                 @Valid @RequestBody CmsDTO.VersionCreateRequest req) {
        return ApiResponse.ok("版本创建成功", versionService.createManual(itemId, req));
    }

    @Operation(summary = "回滚到指定版本")
    @PostMapping("/items/{itemId}/versions/{versionNo}/rollback")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<CmsDTO.VersionDTO> rollback(@PathVariable Long itemId,
                                                   @PathVariable Integer versionNo,
                                                   @RequestParam(required = false) String changeLog) {
        return ApiResponse.ok("已回滚并生成新版本", versionService.rollback(itemId, versionNo, changeLog));
    }

    @Operation(summary = "判定某条目当前是否应建立新版本（版本策略解释）")
    @GetMapping("/items/{itemId}/versions/snapshot-decision")
    public ApiResponse<CmsDTO.SnapshotDecisionDTO> decision(@PathVariable Long itemId) {
        return itemRepository.findById(itemId)
                .map(policyService::evaluate)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail(404, "知识条目不存在"));
    }

    @Operation(summary = "最近版本变更（工作台）")
    @GetMapping("/versions/recent")
    public ApiResponse<List<CmsDTO.VersionDTO>> recent(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(versionService.recentChanges(limit));
    }

    // ---------------- 策略 ----------------

    @Operation(summary = "版本策略列表")
    @GetMapping("/versions/policies")
    public ApiResponse<List<CmsDTO.VersionPolicyDTO>> policies() {
        return ApiResponse.ok(policyService.list());
    }

    @Operation(summary = "新增版本策略")
    @PostMapping("/versions/policies")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CmsDTO.VersionPolicyDTO> createPolicy(
            @Valid @RequestBody CmsDTO.VersionPolicySaveRequest req) {
        return ApiResponse.ok("策略创建成功", policyService.create(req));
    }

    @Operation(summary = "更新版本策略")
    @PutMapping("/versions/policies/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CmsDTO.VersionPolicyDTO> updatePolicy(
            @PathVariable Long id, @Valid @RequestBody CmsDTO.VersionPolicySaveRequest req) {
        return ApiResponse.ok("策略更新成功", policyService.update(id, req));
    }

    @Operation(summary = "删除版本策略")
    @DeleteMapping("/versions/policies/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deletePolicy(@PathVariable Long id) {
        policyService.delete(id);
        return ApiResponse.ok("策略删除成功", null);
    }

    @Operation(summary = "按策略清理超量历史版本")
    @PostMapping("/versions/prune")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Integer> prune() {
        return ApiResponse.ok("清理完成", versionService.pruneAll());
    }
}
