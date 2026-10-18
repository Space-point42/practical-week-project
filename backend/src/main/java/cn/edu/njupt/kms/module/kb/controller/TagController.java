package cn.edu.njupt.kms.module.kb.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.module.kb.dto.KnowledgeDTO;
import cn.edu.njupt.kms.module.kb.entity.Tag;
import cn.edu.njupt.kms.module.kb.repository.TagRepository;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 标签接口。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "07 标签", description = "知识标签的查询与热门标签")
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagRepository tagRepository;

    @Operation(summary = "全部标签（按使用次数排序）")
    @GetMapping
    public ApiResponse<List<KnowledgeDTO.TagDTO>> list(@RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(tagRepository.findAllByOrderByUseCountDescNameAsc(
                        PageRequest.of(0, Math.min(limit, 500))).stream()
                .map(KnowledgeDTO.TagDTO::from)
                .toList());
    }

    @Operation(summary = "热门标签")
    @GetMapping("/hot")
    public ApiResponse<List<KnowledgeDTO.TagDTO>> hot(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(tagRepository.findAllByOrderByUseCountDescNameAsc(
                        PageRequest.of(0, Math.min(limit, 100))).stream()
                .filter(t -> t.getUseCount() != null && t.getUseCount() > 0)
                .map(KnowledgeDTO.TagDTO::from)
                .toList());
    }

    @Operation(summary = "标签联想")
    @GetMapping("/suggest")
    public ApiResponse<List<KnowledgeDTO.TagDTO>> suggest(@RequestParam String keyword) {
        return ApiResponse.ok(tagRepository.search(keyword, PageRequest.of(0, 10)).stream()
                .map(KnowledgeDTO.TagDTO::from)
                .toList());
    }

    @Operation(summary = "清理无用标签")
    @PostMapping("/cleanup")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Integer> cleanup() {
        return ApiResponse.ok("清理完成", tagRepository.deleteOrphans());
    }

    @Operation(summary = "标签实体（供内部调试）")
    @GetMapping("/{id}")
    public ApiResponse<Tag> get(@PathVariable Long id) {
        return ApiResponse.ok(tagRepository.findById(id).orElse(null));
    }
}
