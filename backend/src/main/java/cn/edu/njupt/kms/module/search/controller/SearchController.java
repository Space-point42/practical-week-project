package cn.edu.njupt.kms.module.search.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.module.search.dto.SearchDTO;
import cn.edu.njupt.kms.module.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 搜索引擎接口。
 *
 * <p>检索对匿名用户开放（知识库的可发现性要求），
 * 检索统计仅管理员可见。</p>
 */
@Tag(name = "09 搜索引擎", description = "全文检索、高级检索、联想与热搜")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "统一检索入口（全文/高级/自动）")
    @PostMapping
    public ApiResponse<SearchDTO.SearchResult> search(@RequestBody SearchDTO.SearchRequest req) {
        if (req.getPage() < 1) {
            req.setPage(1);
        }
        if (req.getSize() < 1 || req.getSize() > 100) {
            req.setSize(10);
        }
        return ApiResponse.ok(searchService.search(req));
    }

    @Operation(summary = "简单检索（GET，便于直接分享链接）")
    @GetMapping
    public ApiResponse<SearchDTO.SearchResult> searchGet(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) List<String> assetTypes,
            @RequestParam(required = false) List<Long> tagIds,
            @RequestParam(required = false) List<Long> conceptIds,
            @RequestParam(required = false, defaultValue = "RELEVANCE") String sortBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        SearchDTO.SearchRequest req = new SearchDTO.SearchRequest();
        req.setKeyword(keyword);
        req.setCategoryId(categoryId);
        req.setAssetTypes(assetTypes);
        req.setTagIds(tagIds);
        req.setConceptIds(conceptIds);
        req.setSortBy(sortBy);
        req.setPage(Math.max(page, 1));
        req.setSize(Math.min(Math.max(size, 1), 100));
        return ApiResponse.ok(searchService.search(req));
    }

    @Operation(summary = "高级检索（显式声明条件组合）")
    @PostMapping("/advanced")
    public ApiResponse<SearchDTO.SearchResult> advanced(@RequestBody SearchDTO.SearchRequest req) {
        req.setMode(SearchDTO.Mode.ADVANCED);
        return ApiResponse.ok(searchService.search(req));
    }

    @Operation(summary = "搜索联想")
    @GetMapping("/suggest")
    public ApiResponse<List<SearchDTO.Suggestion>> suggest(@RequestParam(required = false) String keyword,
                                                           @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(searchService.suggest(keyword, Math.min(limit, 20)));
    }

    @Operation(summary = "热搜词")
    @GetMapping("/hot-keywords")
    public ApiResponse<List<SearchDTO.HotKeyword>> hotKeywords(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(searchService.hotKeywords(Math.min(limit, 50)));
    }

    @Operation(summary = "检索统计（含零结果检索词）")
    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SearchDTO.SearchStats> stats(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(searchService.stats(Math.min(limit, 50)));
    }
}
