package cn.edu.njupt.kms.module.ontology.service;

import cn.edu.njupt.kms.common.BizException;
import cn.edu.njupt.kms.module.audit.service.AuditService;
import cn.edu.njupt.kms.module.kb.repository.ItemConceptLinkRepository;
import cn.edu.njupt.kms.module.ontology.dto.OntologyDTO;
import cn.edu.njupt.kms.module.ontology.entity.Concept;
import cn.edu.njupt.kms.module.ontology.entity.Relation;
import cn.edu.njupt.kms.module.ontology.entity.RelationType;
import cn.edu.njupt.kms.module.ontology.repository.ConceptRepository;
import cn.edu.njupt.kms.module.ontology.repository.RelationRepository;
import cn.edu.njupt.kms.module.ontology.repository.RelationTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 本体服务：概念的层次管理、关系三元组管理、图谱构建与简单推理。
 *
 * <p>本模块是题目“用本体对知识的逻辑结构进行建模”要求的落地实现，
 * 对内为知识条目提供标注锚点，对外为检索（查询扩展）与可视化（知识图谱）提供结构支撑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OntologyService {

    private final ConceptRepository conceptRepository;
    private final RelationRepository relationRepository;
    private final RelationTypeRepository relationTypeRepository;
    private final ItemConceptLinkRepository linkRepository;
    private final AuditService auditService;

    // ==================================================================
    // 概念（知识点）
    // ==================================================================

    @Transactional(readOnly = true)
    public List<OntologyDTO.ConceptDTO> listAll() {
        Map<Long, Long> itemCounts = linkCountMap();
        Map<Long, Long> childCounts = childCountMap();
        return conceptRepository.findAllByOrderByLevelAscSortOrderAscIdAsc().stream()
                .map(c -> decorate(OntologyDTO.ConceptDTO.from(c), c, itemCounts, childCounts))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OntologyDTO.ConceptDTO> listByDomain(String domain) {
        Map<Long, Long> itemCounts = linkCountMap();
        Map<Long, Long> childCounts = childCountMap();
        List<Concept> list = StringUtils.hasText(domain)
                ? conceptRepository.findByDomainOrderByIdAsc(domain)
                : conceptRepository.findAllByOrderByLevelAscSortOrderAscIdAsc();
        return list.stream()
                .map(c -> decorate(OntologyDTO.ConceptDTO.from(c), c, itemCounts, childCounts))
                .toList();
    }

    /** 返回层次树（嵌套结构），供前端树形导航使用。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> tree() {
        Map<Long, Long> itemCounts = linkCountMap();
        Map<Long, Long> childCounts = childCountMap();
        List<Concept> all = conceptRepository.findAllByOrderByLevelAscSortOrderAscIdAsc();

        Map<Long, List<Concept>> byParent = all.stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(Concept::getParentId));
        List<Concept> roots = all.stream().filter(Concept::isRoot).toList();

        return roots.stream().map(r -> buildNode(r, byParent, itemCounts, childCounts)).toList();
    }

    private Map<String, Object> buildNode(Concept c, Map<Long, List<Concept>> byParent,
                                          Map<Long, Long> itemCounts, Map<Long, Long> childCounts) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", c.getId());
        node.put("code", c.getCode());
        node.put("name", c.getName());
        node.put("enName", c.getEnName());
        node.put("domain", c.getDomain());
        node.put("level", c.getLevel());
        node.put("difficulty", c.getDifficulty());
        node.put("color", c.getColor());
        node.put("itemCount", itemCounts.getOrDefault(c.getId(), 0L));
        node.put("childCount", childCounts.getOrDefault(c.getId(), 0L));
        List<Concept> children = byParent.getOrDefault(c.getId(), List.of());
        if (!children.isEmpty()) {
            node.put("children", children.stream().map(ch -> buildNode(ch, byParent, itemCounts, childCounts)).toList());
        }
        return node;
    }

    @Transactional(readOnly = true)
    public OntologyDTO.ConceptDTO get(Long id) {
        Concept c = require(id);
        OntologyDTO.ConceptDTO dto = OntologyDTO.ConceptDTO.from(c);
        dto.setItemCount(linkRepository.countByConceptId(id));
        dto.setChildCount(conceptRepository.countByParentId(id));
        if (c.getParentId() != null) {
            conceptRepository.findById(c.getParentId()).ifPresent(p -> dto.setParentName(p.getName()));
        }
        return dto;
    }

    @Transactional
    public OntologyDTO.ConceptDTO create(OntologyDTO.ConceptSaveRequest req) {
        if (conceptRepository.existsByCode(req.getCode())) {
            throw BizException.conflict("概念编码已存在：" + req.getCode());
        }
        Concept c = new Concept();
        apply(c, req);
        c.setBuiltin(0);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());

        Long parentId = req.getParentId();
        if (parentId != null) {
            Concept parent = require(parentId);
            c.setLevel(parent.getLevel() + 1);
        } else {
            c.setLevel(1);
        }
        conceptRepository.save(c);
        // 首次保存拿到自增 id 后才能补齐物化路径
        c.setPath(buildPath(c));
        conceptRepository.save(c);

        auditService.record("CREATE", "CONCEPT", c.getId(), "新增知识点：" + c.getName());
        return OntologyDTO.ConceptDTO.from(c);
    }

    @Transactional
    public OntologyDTO.ConceptDTO update(Long id, OntologyDTO.ConceptSaveRequest req) {
        Concept c = require(id);
        if (!c.getCode().equals(req.getCode()) && conceptRepository.existsByCode(req.getCode())) {
            throw BizException.conflict("概念编码已存在：" + req.getCode());
        }
        boolean parentChanged = !Objects.equals(c.getParentId(), req.getParentId());
        if (parentChanged && req.getParentId() != null) {
            if (req.getParentId().equals(id)) {
                throw BizException.badRequest("父概念不能是自身");
            }
            if (isDescendant(req.getParentId(), c)) {
                throw BizException.badRequest("不能将概念移动到自己的后代节点下，会产生环");
            }
        }
        apply(c, req);
        c.setUpdatedAt(LocalDateTime.now());

        if (parentChanged) {
            if (req.getParentId() != null) {
                Concept parent = require(req.getParentId());
                c.setLevel(parent.getLevel() + 1);
            } else {
                c.setLevel(1);
            }
            conceptRepository.save(c);
            c.setPath(buildPath(c));
            conceptRepository.save(c);
            // 递归修正所有后代的 level 与 path
            refreshDescendants(c);
        } else {
            conceptRepository.save(c);
        }

        auditService.record("UPDATE", "CONCEPT", id, "更新知识点：" + c.getName());
        return OntologyDTO.ConceptDTO.from(c);
    }

    @Transactional
    public void delete(Long id) {
        Concept c = require(id);
        if (c.getBuiltin() != null && c.getBuiltin() == 1) {
            throw BizException.badRequest("系统内置知识点不允许删除");
        }
        long children = conceptRepository.countByParentId(id);
        if (children > 0) {
            throw BizException.badRequest("该知识点下仍有 %d 个子知识点，请先删除或迁移子节点".formatted(children));
        }
        long linked = linkRepository.countByConceptId(id);
        if (linked > 0) {
            throw BizException.badRequest("该知识点已被 %d 条知识条目引用，请先解除标注".formatted(linked));
        }
        relationRepository.deleteByEitherEnd(id);
        linkRepository.deleteByConceptId(id);
        conceptRepository.delete(c);
        auditService.record("DELETE", "CONCEPT", id, "删除知识点：" + c.getName());
    }

    /** 概念检索（按名称/英文名/别名/编码）。 */
    @Transactional(readOnly = true)
    public List<OntologyDTO.ConceptDTO> search(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        return conceptRepository.search(keyword.trim()).stream()
                .map(OntologyDTO.ConceptDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> listDomains() {
        return conceptRepository.listDomains();
    }

    // ==================================================================
    // 关系类型与关系三元组
    // ==================================================================

    @Transactional(readOnly = true)
    public List<OntologyDTO.RelationTypeDTO> listRelationTypes() {
        Map<String, Long> counts = relationRepository.countGroupByType().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> ((Number) r[1]).longValue()));
        return relationTypeRepository.findAllByOrderBySortOrderAsc().stream()
                .map(t -> {
                    OntologyDTO.RelationTypeDTO dto = OntologyDTO.RelationTypeDTO.from(t);
                    dto.setCount(counts.getOrDefault(t.getCode(), 0L));
                    return dto;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OntologyDTO.RelationDTO> listRelations(Long conceptId, String relationType) {
        List<Relation> relations;
        if (conceptId != null) {
            relations = relationRepository.findByEitherEnd(conceptId);
        } else if (StringUtils.hasText(relationType)) {
            relations = relationRepository.findByRelationType(relationType);
        } else {
            relations = relationRepository.findAll();
        }
        return toDTOs(relations);
    }

    @Transactional
    public OntologyDTO.RelationDTO createRelation(OntologyDTO.RelationSaveRequest req) {
        if (Objects.equals(req.getSourceId(), req.getTargetId())) {
            throw BizException.badRequest("关系的源与目标不能是同一概念");
        }
        Concept source = require(req.getSourceId());
        Concept target = require(req.getTargetId());
        RelationType type = requireType(req.getRelationType());

        if (relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                source.getId(), target.getId(), type.getCode())) {
            throw BizException.conflict("该关系已存在");
        }
        Relation r = new Relation();
        r.setSourceId(source.getId());
        r.setTargetId(target.getId());
        r.setRelationType(type.getCode());
        r.setWeight(req.getWeight() == null ? BigDecimal.ONE : BigDecimal.valueOf(req.getWeight()));
        r.setDescription(StringUtils.hasText(req.getDescription())
                ? req.getDescription()
                : "%s %s %s".formatted(source.getName(), type.getName(), target.getName()));
        r.setBuiltin(0);
        r.setCreatedAt(LocalDateTime.now());
        relationRepository.save(r);

        // 对称关系自动补一条反向边，保证图谱无向语义成立
        if (type.getDirected() != null && type.getDirected() == 0
                && !relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                        target.getId(), source.getId(), type.getCode())) {
            Relation inverse = new Relation();
            inverse.setSourceId(target.getId());
            inverse.setTargetId(source.getId());
            inverse.setRelationType(type.getCode());
            inverse.setWeight(r.getWeight());
            inverse.setDescription(r.getDescription());
            inverse.setBuiltin(0);
            inverse.setCreatedAt(LocalDateTime.now());
            relationRepository.save(inverse);
        }

        auditService.record("CREATE", "RELATION", r.getId(),
                "新增关系：%s -[%s]-> %s".formatted(source.getName(), type.getName(), target.getName()));
        return OntologyDTO.RelationDTO.from(r, source, target, type.getName());
    }

    @Transactional
    public void deleteRelation(Long id) {
        Relation r = relationRepository.findById(id).orElseThrow(() -> BizException.notFound("关系"));
        relationRepository.delete(r);
        auditService.record("DELETE", "RELATION", id, "删除关系 #" + id);
    }

    // ==================================================================
    // 图谱
    // ==================================================================

    /**
     * 构建知识图谱。
     *
     * @param rootId   若指定，则只取该概念及其后代构成的子图；为空则返回全图
     * @param domain   按知识域过滤
     * @param withIsolated 是否包含没有任何关系的孤立节点
     */
    @Transactional(readOnly = true)
    public OntologyDTO.GraphDTO graph(Long rootId, String domain, boolean withIsolated, Long highlightId, int limit) {
        List<Concept> concepts;
        if (rootId != null) {
            Concept root = require(rootId);
            concepts = conceptRepository.findSubtree(root.getPath());
        } else if (StringUtils.hasText(domain)) {
            concepts = conceptRepository.findByDomainOrderByIdAsc(domain);
        } else {
            concepts = conceptRepository.findAllByOrderByLevelAscSortOrderAscIdAsc();
        }
        if (limit > 0 && concepts.size() > limit) {
            concepts = concepts.subList(0, limit);
        }

        Map<Long, Concept> conceptMap = concepts.stream()
                .collect(Collectors.toMap(Concept::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<Long> ids = conceptMap.keySet();

        List<Relation> relations = ids.isEmpty() ? List.of()
                : relationRepository.findWithin(new ArrayList<>(ids));

        Map<Long, Long> itemCounts = linkCountMap();
        Map<String, RelationType> typeMap = relationTypeRepository.findAll().stream()
                .collect(Collectors.toMap(RelationType::getCode, Function.identity(), (a, b) -> a));

        // ---- 节点 ----
        Set<Long> connected = new HashSet<>();
        relations.forEach(r -> {
            connected.add(r.getSourceId());
            connected.add(r.getTargetId());
        });

        List<OntologyDTO.GraphNode> nodes = new ArrayList<>();
        for (Concept c : concepts) {
            if (!withIsolated && !connected.contains(c.getId())) {
                continue;
            }
            OntologyDTO.GraphNode n = new OntologyDTO.GraphNode();
            n.setId(c.getId());
            n.setCode(c.getCode());
            n.setName(c.getName());
            n.setDomain(c.getDomain());
            n.setLevel(c.getLevel());
            n.setDifficulty(c.getDifficulty());
            n.setCategory(c.getDomain());
            n.setColor(StringUtils.hasText(c.getColor()) ? c.getColor() : defaultColor(c.getDomain()));
            long ic = itemCounts.getOrDefault(c.getId(), 0L);
            n.setItemCount(ic);
            // 节点大小：随支撑该知识点的条目数增长，但做对数压缩避免个别节点过大
            n.setValue(12 + Math.log1p(ic) * 8 + (4 - Math.min(c.getLevel(), 4)) * 2);
            n.setHighlight(highlightId != null && highlightId.equals(c.getId()));
            nodes.add(n);
        }

        // ---- 边 ----
        Set<Long> nodeIds = nodes.stream().map(OntologyDTO.GraphNode::getId).collect(Collectors.toSet());
        List<OntologyDTO.GraphEdge> edges = new ArrayList<>();
        for (Relation r : relations) {
            if (!nodeIds.contains(r.getSourceId()) || !nodeIds.contains(r.getTargetId())) {
                continue;
            }
            RelationType t = typeMap.get(r.getRelationType());
            OntologyDTO.GraphEdge e = new OntologyDTO.GraphEdge();
            e.setId(r.getId());
            e.setSource(r.getSourceId());
            e.setTarget(r.getTargetId());
            e.setRelationType(r.getRelationType());
            e.setRelationTypeName(t == null ? r.getRelationType() : t.getName());
            e.setLabel(t == null ? r.getRelationType() : t.getName());
            e.setWeight(r.getWeight() == null ? 1.0 : r.getWeight().doubleValue());
            e.setColor(t == null ? "#898781" : t.getColor());
            edges.add(e);
        }

        OntologyDTO.GraphDTO g = new OntologyDTO.GraphDTO();
        g.setNodes(nodes);
        g.setEdges(edges);
        g.setRelationTypes(listRelationTypes());
        g.setNodeCount(nodes.size());
        g.setEdgeCount(edges.size());
        return g;
    }

    /** 某概念的邻域子图（一跳或 N 跳），用于“知识地图”定位。 */
    @Transactional(readOnly = true)
    public OntologyDTO.GraphDTO neighborhood(Long conceptId, int depth) {
        Concept center = require(conceptId);
        Set<Long> visited = new LinkedHashSet<>();
        visited.add(conceptId);
        Set<Long> frontier = new LinkedHashSet<>(Set.of(conceptId));

        for (int d = 0; d < Math.max(depth, 1); d++) {
            Set<Long> next = new LinkedHashSet<>();
            for (Long id : frontier) {
                for (Relation r : relationRepository.findByEitherEnd(id)) {
                    Long other = r.getSourceId().equals(id) ? r.getTargetId() : r.getSourceId();
                    if (visited.add(other)) {
                        next.add(other);
                    }
                }
            }
            frontier = next;
            if (frontier.isEmpty()) {
                break;
            }
        }

        Map<Long, Concept> map = conceptRepository.findAllById(visited).stream()
                .collect(Collectors.toMap(Concept::getId, Function.identity()));
        Map<Long, Long> itemCounts = linkCountMap();
        Map<String, RelationType> typeMap = relationTypeRepository.findAll().stream()
                .collect(Collectors.toMap(RelationType::getCode, Function.identity(), (a, b) -> a));

        List<OntologyDTO.GraphNode> nodes = new ArrayList<>();
        for (Long id : visited) {
            Concept c = map.get(id);
            if (c == null) {
                continue;
            }
            OntologyDTO.GraphNode n = new OntologyDTO.GraphNode();
            n.setId(c.getId());
            n.setCode(c.getCode());
            n.setName(c.getName());
            n.setDomain(c.getDomain());
            n.setLevel(c.getLevel());
            n.setDifficulty(c.getDifficulty());
            n.setCategory(c.getDomain());
            n.setColor(StringUtils.hasText(c.getColor()) ? c.getColor() : defaultColor(c.getDomain()));
            long ic = itemCounts.getOrDefault(c.getId(), 0L);
            n.setItemCount(ic);
            n.setValue(12 + Math.log1p(ic) * 8);
            n.setHighlight(c.getId().equals(conceptId));
            nodes.add(n);
        }

        List<OntologyDTO.GraphEdge> edges = new ArrayList<>();
        for (Relation r : relationRepository.findWithin(new ArrayList<>(visited))) {
            RelationType t = typeMap.get(r.getRelationType());
            OntologyDTO.GraphEdge e = new OntologyDTO.GraphEdge();
            e.setId(r.getId());
            e.setSource(r.getSourceId());
            e.setTarget(r.getTargetId());
            e.setRelationType(r.getRelationType());
            e.setRelationTypeName(t == null ? r.getRelationType() : t.getName());
            e.setLabel(t == null ? r.getRelationType() : t.getName());
            e.setWeight(r.getWeight() == null ? 1.0 : r.getWeight().doubleValue());
            e.setColor(t == null ? "#898781" : t.getColor());
            edges.add(e);
        }

        OntologyDTO.GraphDTO g = new OntologyDTO.GraphDTO();
        g.setNodes(nodes);
        g.setEdges(edges);
        g.setRelationTypes(listRelationTypes());
        g.setNodeCount(nodes.size());
        g.setEdgeCount(edges.size());
        return g;
    }

    // ==================================================================
    // 统计与推理
    // ==================================================================

    @Transactional(readOnly = true)
    public OntologyDTO.OntologyStatsDTO stats() {
        List<Concept> all = conceptRepository.findAll();
        List<Relation> relations = relationRepository.findAll();
        List<RelationType> types = relationTypeRepository.findAll();

        OntologyDTO.OntologyStatsDTO dto = new OntologyDTO.OntologyStatsDTO();
        dto.setConceptCount(all.size());
        dto.setRelationCount(relations.size());
        dto.setRelationTypeCount(types.size());
        dto.setMaxDepth(all.stream().mapToInt(c -> c.getLevel() == null ? 1 : c.getLevel()).max().orElse(0));

        dto.setDomainDistribution(all.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getDomain() == null ? "未分类" : c.getDomain(),
                        Collectors.counting())));

        Map<String, String> typeNames = types.stream()
                .collect(Collectors.toMap(RelationType::getCode, RelationType::getName));
        dto.setRelationTypeDistribution(relations.stream()
                .collect(Collectors.groupingBy(
                        r -> typeNames.getOrDefault(r.getRelationType(), r.getRelationType()),
                        Collectors.counting())));

        long covered = linkRepository.countDistinctConcepts();
        dto.setCoveredConceptCount(covered);
        dto.setCoverage(all.isEmpty() ? 0 : Math.round(covered * 10000.0 / all.size()) / 100.0);
        return dto;
    }

    /**
     * 本体推理：从起点概念出发，沿指定关系类型逐层展开。
     *
     * <p>例如从“软件测试”出发沿 {@code PREREQUISITE_OF} 展开，
     * 可得到学习路径；沿 {@code PART_OF} 展开可得到知识的组成结构。</p>
     */
    @Transactional(readOnly = true)
    public OntologyDTO.InferenceDTO infer(Long originId, String relationType, int maxDepth) {
        Concept origin = require(originId);
        RelationType type = requireType(relationType);
        int depth = Math.min(Math.max(maxDepth, 1), 5);

        Map<Long, Concept> conceptMap = conceptRepository.findAll().stream()
                .collect(Collectors.toMap(Concept::getId, Function.identity()));

        Map<Long, List<Relation>> outgoing = relationRepository.findAll().stream()
                .filter(r -> r.getRelationType().equals(type.getCode()))
                .collect(Collectors.groupingBy(Relation::getSourceId));

        List<OntologyDTO.InferenceLevel> levels = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(originId);
        Set<Long> frontier = new LinkedHashSet<>(Set.of(originId));

        for (int d = 1; d <= depth; d++) {
            Set<Long> next = new LinkedHashSet<>();
            List<OntologyDTO.ConceptDTO> levelConcepts = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            for (Long id : frontier) {
                for (Relation r : outgoing.getOrDefault(id, List.of())) {
                    Concept target = conceptMap.get(r.getTargetId());
                    if (target == null || !visited.add(r.getTargetId())) {
                        continue;
                    }
                    next.add(r.getTargetId());
                    levelConcepts.add(OntologyDTO.ConceptDTO.from(target));
                    Concept from = conceptMap.get(id);
                    paths.add("%s -[%s]-> %s".formatted(
                            from == null ? id : from.getName(), type.getName(), target.getName()));
                }
            }
            if (levelConcepts.isEmpty()) {
                break;
            }
            OntologyDTO.InferenceLevel lv = new OntologyDTO.InferenceLevel();
            lv.setDepth(d);
            lv.setConcepts(levelConcepts);
            lv.setPaths(paths);
            levels.add(lv);
            frontier = next;
        }

        OntologyDTO.InferenceDTO dto = new OntologyDTO.InferenceDTO();
        dto.setOriginId(originId);
        dto.setOriginName(origin.getName());
        dto.setRelationType(relationType);
        dto.setMaxDepth(depth);
        dto.setLevels(levels);
        return dto;
    }

    /**
     * 实体链接：从一段文本中识别出本体概念。
     *
     * <p>实现方式为「词典匹配 + 别名扩展」：把概念名称与别名构成词典，
     * 按长度倒序扫描文本以避免短词优先匹配（如先匹配“需求分析”再匹配“需求”）。
     * 该能力服务于自动标注、检索的查询扩展与 RAG 的上下文增强。</p>
     *
     * @param text  待识别文本
     * @param limit 最多返回的概念数
     */
    @Transactional(readOnly = true)
    public List<OntologyDTO.ConceptDTO> link(String text, int limit) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<Concept> all = conceptRepository.findAll();
        // 词典：别名/名称 -> 概念
        record Term(String word, Concept concept) {
        }
        List<Term> dict = new ArrayList<>();
        for (Concept c : all) {
            dict.add(new Term(c.getName(), c));
            if (StringUtils.hasText(c.getEnName())) {
                dict.add(new Term(c.getEnName(), c));
            }
            for (String alias : OntologyDTO.ConceptDTO.splitAliases(c.getAliases())) {
                dict.add(new Term(alias, c));
            }
        }
        // 长词优先，避免“需求”抢占“需求分析”
        dict.sort((a, b) -> Integer.compare(b.word().length(), a.word().length()));

        Map<Long, OntologyDTO.ConceptDTO> hit = new LinkedHashMap<>();
        String lower = text.toLowerCase();
        for (Term t : dict) {
            if (t.word().isEmpty()) {
                continue;
            }
            if (hit.size() >= limit) {
                break;
            }
            String w = t.word().toLowerCase();
            if (lower.contains(w) && !hit.containsKey(t.concept().getId())) {
                hit.put(t.concept().getId(), OntologyDTO.ConceptDTO.from(t.concept()));
            }
        }
        return new ArrayList<>(hit.values());
    }

    /**
     * 概念扩展：给定概念集合，沿本体关系扩展出语义相关的概念。
     *
     * <p>用于检索的查询扩展：用户搜“需求分析”，系统顺带召回“需求获取”“需求验证”
     * 等前置/后续知识点下的资料。</p>
     */
    @Transactional(readOnly = true)
    public List<Concept> expand(List<Long> conceptIds, int limit) {
        if (conceptIds == null || conceptIds.isEmpty()) {
            return List.of();
        }
        // 允许参与扩展的关系：语义距离近的几类
        Set<String> allowed = Set.of(
                RelationType.SUB_CLASS_OF, RelationType.PART_OF,
                RelationType.PREREQUISITE_OF, RelationType.RELATED_TO);

        Map<Long, Concept> map = conceptRepository.findAll().stream()
                .collect(Collectors.toMap(Concept::getId, Function.identity()));

        Set<Long> seeds = new HashSet<>(conceptIds);
        LinkedHashMap<Long, Concept> result = new LinkedHashMap<>();

        for (Long id : conceptIds) {
            for (Relation r : relationRepository.findByEitherEnd(id)) {
                if (!allowed.contains(r.getRelationType())) {
                    continue;
                }
                Long other = r.getSourceId().equals(id) ? r.getTargetId() : r.getSourceId();
                if (seeds.contains(other) || result.containsKey(other)) {
                    continue;
                }
                Concept c = map.get(other);
                if (c != null) {
                    result.put(other, c);
                }
                if (result.size() >= limit) {
                    return new ArrayList<>(result.values());
                }
            }
            // 同时纳入直接的父/子节点，保证层次上的相邻知识点也能被召回
            Concept self = map.get(id);
            if (self != null) {
                if (self.getParentId() != null && !seeds.contains(self.getParentId())
                        && !result.containsKey(self.getParentId())) {
                    Concept p = map.get(self.getParentId());
                    if (p != null) {
                        result.put(p.getId(), p);
                    }
                }
                for (Concept child : conceptRepository.findByParentIdOrderBySortOrderAscIdAsc(id)) {
                    if (!seeds.contains(child.getId()) && !result.containsKey(child.getId())) {
                        result.put(child.getId(), child);
                    }
                }
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(result.values());
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    public Concept require(Long id) {
        return conceptRepository.findById(id).orElseThrow(() -> BizException.notFound("知识点"));
    }

    private RelationType requireType(String code) {
        return relationTypeRepository.findByCode(code)
                .orElseThrow(() -> BizException.badRequest("未知的关系类型：" + code));
    }

    private void apply(Concept c, OntologyDTO.ConceptSaveRequest req) {
        c.setCode(req.getCode());
        c.setName(req.getName());
        c.setEnName(req.getEnName());
        c.setAliases(req.getAliases() == null ? null : String.join(",", req.getAliases()));
        c.setDescription(req.getDescription());
        c.setParentId(req.getParentId());
        c.setDomain(req.getDomain());
        c.setDifficulty(req.getDifficulty() == null ? 3 : req.getDifficulty());
        c.setImportance(req.getImportance() == null ? 3 : req.getImportance());
        c.setColor(req.getColor());
        c.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
    }

    private String buildPath(Concept c) {
        if (c.getParentId() == null) {
            return "/" + c.getId() + "/";
        }
        Concept parent = conceptRepository.findById(c.getParentId()).orElse(null);
        String parentPath = (parent != null && parent.getPath() != null) ? parent.getPath() : "/";
        return parentPath + c.getId() + "/";
    }

    /** 递归刷新子树中所有后代的 level 与 path。 */
    private void refreshDescendants(Concept root) {
        List<Concept> children = conceptRepository.findByParentIdOrderBySortOrderAscIdAsc(root.getId());
        for (Concept child : children) {
            child.setLevel(root.getLevel() + 1);
            child.setPath(buildPath(child));
            conceptRepository.save(child);
            refreshDescendants(child);
        }
    }

    /** 判断 candidate 是否为 concept 的后代。 */
    private boolean isDescendant(Long candidateId, Concept concept) {
        Concept candidate = conceptRepository.findById(candidateId).orElse(null);
        if (candidate == null || candidate.getPath() == null || concept.getPath() == null) {
            return false;
        }
        return candidate.getPath().startsWith(concept.getPath());
    }

    private OntologyDTO.ConceptDTO decorate(OntologyDTO.ConceptDTO dto, Concept c,
                                            Map<Long, Long> itemCounts, Map<Long, Long> childCounts) {
        dto.setItemCount(itemCounts.getOrDefault(c.getId(), 0L));
        dto.setChildCount(childCounts.getOrDefault(c.getId(), 0L));
        return dto;
    }

    private Map<Long, Long> linkCountMap() {
        return linkRepository.countGroupByConcept().stream()
                .collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> ((Number) r[1]).longValue()));
    }

    private Map<Long, Long> childCountMap() {
        return conceptRepository.findAll().stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(Concept::getParentId, Collectors.counting()));
    }

    private List<OntologyDTO.RelationDTO> toDTOs(List<Relation> relations) {
        if (relations.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new HashSet<>();
        relations.forEach(r -> {
            ids.add(r.getSourceId());
            ids.add(r.getTargetId());
        });
        Map<Long, Concept> map = conceptRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Concept::getId, Function.identity()));
        Map<String, String> typeNames = relationTypeRepository.findAll().stream()
                .collect(Collectors.toMap(RelationType::getCode, RelationType::getName));

        return relations.stream()
                .map(r -> OntologyDTO.RelationDTO.from(r,
                        map.get(r.getSourceId()), map.get(r.getTargetId()),
                        typeNames.get(r.getRelationType())))
                .toList();
    }

    /**
     * 按知识域分配稳定配色。
     *
     * <p>三个知识域使用一组经过校验的分类色（两两之间在色觉缺陷模拟下
     * ΔE ≥ 9），保证图谱上不同域一眼可辨；未知域退回中性灰。</p>
     */
    private String defaultColor(String domain) {
        if (domain == null) {
            return "#898781";
        }
        return switch (domain) {
            case "软件工程" -> "#2a78d6";
            case "数据库" -> "#1baf7a";
            case "知识管理" -> "#eb6834";
            default -> "#898781";
        };
    }
}
