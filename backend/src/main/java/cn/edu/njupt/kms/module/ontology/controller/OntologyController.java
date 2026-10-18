package cn.edu.njupt.kms.module.ontology.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.module.ontology.dto.OntologyDTO;
import cn.edu.njupt.kms.module.ontology.service.OntologyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识本体接口。
 *
 * <p>查询类接口对匿名用户开放（便于知识导航），维护类接口限管理员。</p>
 */
@Tag(name = "03 知识本体", description = "知识点（概念）、关系类型、知识图谱与推理")
@RestController
@RequestMapping("/api/ontology")
@RequiredArgsConstructor
public class OntologyController {

    private final OntologyService ontologyService;

    // ---------------- 概念 ----------------

    @Operation(summary = "全部知识点列表")
    @GetMapping("/concepts")
    public ApiResponse<List<OntologyDTO.ConceptDTO>> concepts(
            @RequestParam(required = false) String domain) {
        return ApiResponse.ok(ontologyService.listByDomain(domain));
    }

    @Operation(summary = "知识点层次树")
    @GetMapping("/concepts/tree")
    public ApiResponse<List<Map<String, Object>>> tree() {
        return ApiResponse.ok(ontologyService.tree());
    }

    @Operation(summary = "知识点详情")
    @GetMapping("/concepts/{id}")
    public ApiResponse<OntologyDTO.ConceptDTO> concept(@PathVariable Long id) {
        return ApiResponse.ok(ontologyService.get(id));
    }

    @Operation(summary = "知识点检索（名称/英文名/别名/编码）")
    @GetMapping("/concepts/search")
    public ApiResponse<List<OntologyDTO.ConceptDTO>> searchConcepts(@RequestParam String keyword) {
        return ApiResponse.ok(ontologyService.search(keyword));
    }

    @Operation(summary = "知识域列表")
    @GetMapping("/domains")
    public ApiResponse<List<String>> domains() {
        return ApiResponse.ok(ontologyService.listDomains());
    }

    @Operation(summary = "新增知识点")
    @PostMapping("/concepts")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<OntologyDTO.ConceptDTO> createConcept(@Valid @RequestBody OntologyDTO.ConceptSaveRequest req) {
        return ApiResponse.ok("新增成功", ontologyService.create(req));
    }

    @Operation(summary = "更新知识点")
    @PutMapping("/concepts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<OntologyDTO.ConceptDTO> updateConcept(@PathVariable Long id,
                                                             @Valid @RequestBody OntologyDTO.ConceptSaveRequest req) {
        return ApiResponse.ok("更新成功", ontologyService.update(id, req));
    }

    @Operation(summary = "删除知识点")
    @DeleteMapping("/concepts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteConcept(@PathVariable Long id) {
        ontologyService.delete(id);
        return ApiResponse.ok("删除成功", null);
    }

    // ---------------- 关系 ----------------

    @Operation(summary = "关系类型字典（含各类型数量）")
    @GetMapping("/relation-types")
    public ApiResponse<List<OntologyDTO.RelationTypeDTO>> relationTypes() {
        return ApiResponse.ok(ontologyService.listRelationTypes());
    }

    @Operation(summary = "关系列表（可按概念或类型过滤）")
    @GetMapping("/relations")
    public ApiResponse<List<OntologyDTO.RelationDTO>> relations(
            @RequestParam(required = false) Long conceptId,
            @RequestParam(required = false) String relationType) {
        return ApiResponse.ok(ontologyService.listRelations(conceptId, relationType));
    }

    @Operation(summary = "新增关系")
    @PostMapping("/relations")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<OntologyDTO.RelationDTO> createRelation(@Valid @RequestBody OntologyDTO.RelationSaveRequest req) {
        return ApiResponse.ok("新增成功", ontologyService.createRelation(req));
    }

    @Operation(summary = "删除关系")
    @DeleteMapping("/relations/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteRelation(@PathVariable Long id) {
        ontologyService.deleteRelation(id);
        return ApiResponse.ok("删除成功", null);
    }

    // ---------------- 图谱 / 统计 / 推理 ----------------

    @Operation(summary = "知识图谱数据（ECharts graph 格式）")
    @GetMapping("/graph")
    public ApiResponse<OntologyDTO.GraphDTO> graph(
            @RequestParam(required = false) Long rootId,
            @RequestParam(required = false) String domain,
            @RequestParam(defaultValue = "true") boolean withIsolated,
            @RequestParam(required = false) Long highlightId,
            @RequestParam(defaultValue = "0") int limit) {
        return ApiResponse.ok(ontologyService.graph(rootId, domain, withIsolated, highlightId, limit));
    }

    @Operation(summary = "某知识点的 N 跳邻域子图")
    @GetMapping("/graph/neighborhood/{conceptId}")
    public ApiResponse<OntologyDTO.GraphDTO> neighborhood(@PathVariable Long conceptId,
                                                          @RequestParam(defaultValue = "1") int depth) {
        return ApiResponse.ok(ontologyService.neighborhood(conceptId, depth));
    }

    @Operation(summary = "本体统计（概念数/关系数/覆盖率等）")
    @GetMapping("/stats")
    public ApiResponse<OntologyDTO.OntologyStatsDTO> stats() {
        return ApiResponse.ok(ontologyService.stats());
    }

    @Operation(summary = "沿指定关系推理展开（如学习路径）")
    @GetMapping("/infer")
    public ApiResponse<OntologyDTO.InferenceDTO> infer(@RequestParam Long conceptId,
                                                       @RequestParam String relationType,
                                                       @RequestParam(defaultValue = "3") int depth) {
        return ApiResponse.ok(ontologyService.infer(conceptId, relationType, depth));
    }

    @Operation(summary = "实体链接：从文本识别知识点")
    @GetMapping("/link")
    public ApiResponse<List<OntologyDTO.ConceptDTO>> link(@RequestParam String text,
                                                          @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(ontologyService.link(text, limit));
    }
}
