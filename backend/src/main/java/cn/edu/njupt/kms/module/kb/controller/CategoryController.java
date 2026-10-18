package cn.edu.njupt.kms.module.kb.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.module.kb.dto.KnowledgeDTO;
import cn.edu.njupt.kms.module.kb.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 知识分类接口（层次结构的增删改查）。 */
@Tag(name = "06 知识分类", description = "分类树的维护与查询")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "分类列表（平铺，带层级信息）")
    @GetMapping
    public ApiResponse<List<KnowledgeDTO.CategoryDTO>> list() {
        return ApiResponse.ok(categoryService.listAll());
    }

    @Operation(summary = "分类树")
    @GetMapping("/tree")
    public ApiResponse<List<KnowledgeDTO.CategoryDTO>> tree() {
        return ApiResponse.ok(categoryService.tree());
    }

    @Operation(summary = "分类详情")
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDTO.CategoryDTO> get(@PathVariable Long id) {
        return ApiResponse.ok(categoryService.get(id));
    }

    @Operation(summary = "新增分类")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<KnowledgeDTO.CategoryDTO> create(@Valid @RequestBody KnowledgeDTO.CategorySaveRequest req) {
        return ApiResponse.ok("创建成功", categoryService.create(req));
    }

    @Operation(summary = "更新分类")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<KnowledgeDTO.CategoryDTO> update(@PathVariable Long id,
                                                        @Valid @RequestBody KnowledgeDTO.CategorySaveRequest req) {
        return ApiResponse.ok("更新成功", categoryService.update(id, req));
    }

    @Operation(summary = "删除分类")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ApiResponse.ok("删除成功", null);
    }

    @Operation(summary = "重算分类条目计数")
    @PostMapping("/recalculate")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> recalculate() {
        categoryService.recalculateCounts();
        return ApiResponse.ok("重算完成", null);
    }
}
