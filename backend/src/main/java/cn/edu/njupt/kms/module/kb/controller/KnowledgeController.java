package cn.edu.njupt.kms.module.kb.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.common.PageResult;
import cn.edu.njupt.kms.module.kb.dto.KnowledgeDTO;
import cn.edu.njupt.kms.module.kb.service.KnowledgeItemService;
import cn.edu.njupt.kms.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库接口。
 *
 * <p>浏览类接口匿名可访问，写入类接口要求编辑及以上权限。</p>
 */
@Tag(name = "04 知识库", description = "知识条目的浏览、创建、检索与管理")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeItemService itemService;

    // ---------------- 浏览 ----------------

    @Operation(summary = "知识条目列表（公开，仅已发布）")
    @GetMapping
    public ApiResponse<PageResult<KnowledgeDTO.ItemDTO>> page(KnowledgeDTO.ItemQuery query) {
        return ApiResponse.ok(itemService.page(query, false));
    }

    @Operation(summary = "知识条目详情")
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDTO.ItemDTO> detail(@PathVariable Long id) {
        return ApiResponse.ok(itemService.detail(id, true));
    }

    @Operation(summary = "热门知识")
    @GetMapping("/hot")
    public ApiResponse<List<KnowledgeDTO.ItemDTO>> hot(@RequestParam(defaultValue = "8") int limit) {
        return ApiResponse.ok(itemService.hot(Math.min(limit, 50)));
    }

    @Operation(summary = "最新知识")
    @GetMapping("/recent")
    public ApiResponse<List<KnowledgeDTO.ItemDTO>> recent(@RequestParam(defaultValue = "8") int limit) {
        return ApiResponse.ok(itemService.recent(Math.min(limit, 50)));
    }

    @Operation(summary = "按本体知识点查询关联知识")
    @GetMapping("/by-concepts")
    public ApiResponse<List<KnowledgeDTO.ItemDTO>> byConcepts(@RequestParam List<Long> conceptIds,
                                                              @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(itemService.byConcepts(conceptIds, limit));
    }

    @Operation(summary = "点赞/取消点赞")
    @PostMapping("/{id}/like")
    public ApiResponse<Map<String, Object>> like(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "true") boolean liked) {
        int count = itemService.like(id, liked);
        return ApiResponse.ok(Map.of("likeCount", count));
    }

    // ---------------- 管理 ----------------

    @Operation(summary = "管理端列表（含草稿/待审）")
    @GetMapping("/manage")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<PageResult<KnowledgeDTO.ItemDTO>> manage(KnowledgeDTO.ItemQuery query) {
        return ApiResponse.ok(itemService.page(query, true));
    }

    @Operation(summary = "我创建的内容")
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<PageResult<KnowledgeDTO.ItemDTO>> mine(KnowledgeDTO.ItemQuery query) {
        return ApiResponse.ok(itemService.mine(query));
    }

    @Operation(summary = "新建知识条目")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<KnowledgeDTO.ItemDTO> create(@Valid @RequestBody KnowledgeDTO.ItemSaveRequest req) {
        return ApiResponse.ok("创建成功", itemService.create(req));
    }

    @Operation(summary = "更新知识条目")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<KnowledgeDTO.ItemDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody KnowledgeDTO.ItemSaveRequest req) {
        return ApiResponse.ok("保存成功", itemService.update(id, req));
    }

    @Operation(summary = "提交审核")
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<KnowledgeDTO.ItemDTO> submit(@PathVariable Long id) {
        return ApiResponse.ok("已提交审核", itemService.submit(id));
    }

    @Operation(summary = "发布")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<KnowledgeDTO.ItemDTO> publish(@PathVariable Long id,
                                                     @RequestParam(required = false) String changeLog) {
        return ApiResponse.ok("发布成功", itemService.publish(id, changeLog));
    }

    @Operation(summary = "撤回发布")
    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<KnowledgeDTO.ItemDTO> unpublish(@PathVariable Long id) {
        return ApiResponse.ok("已撤回", itemService.unpublish(id));
    }

    @Operation(summary = "归档")
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<KnowledgeDTO.ItemDTO> archive(@PathVariable Long id,
                                                     @RequestParam(required = false) String reason) {
        return ApiResponse.ok("已归档", itemService.archive(id, reason));
    }

    @Operation(summary = "取消归档")
    @PostMapping("/{id}/unarchive")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<KnowledgeDTO.ItemDTO> unarchive(@PathVariable Long id) {
        return ApiResponse.ok("已恢复", itemService.unarchive(id));
    }

    @Operation(summary = "删除（逻辑删除）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        itemService.delete(id);
        return ApiResponse.ok("删除成功", null);
    }

    @Operation(summary = "当前用户权限探测")
    @GetMapping("/my-permission")
    public ApiResponse<Map<String, Object>> myPermission() {
        return ApiResponse.ok(Map.of(
                "authenticated", SecurityUtils.currentPrincipal().isPresent(),
                "canEdit", SecurityUtils.canEdit(),
                "isAdmin", SecurityUtils.isAdmin(),
                "username", SecurityUtils.currentUsername() == null ? "" : SecurityUtils.currentUsername()));
    }
}
