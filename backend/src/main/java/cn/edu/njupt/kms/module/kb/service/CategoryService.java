package cn.edu.njupt.kms.module.kb.service;

import cn.edu.njupt.kms.common.BizException;
import cn.edu.njupt.kms.module.audit.service.AuditService;
import cn.edu.njupt.kms.module.kb.dto.KnowledgeDTO;
import cn.edu.njupt.kms.module.kb.entity.Category;
import cn.edu.njupt.kms.module.kb.repository.CategoryRepository;
import cn.edu.njupt.kms.module.kb.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分类服务：维护知识分类的层次树。
 *
 * <p>与本体概念的区别：分类是「内容管理者视角」的收纳结构（如“课程资料/技术报告”），
 * 本体是「知识视角」的语义结构（如“软件测试 is-a 软件工程”）。两者互相独立、可交叉引用。</p>
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final KnowledgeItemRepository itemRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<KnowledgeDTO.CategoryDTO> listAll() {
        return decorate(categoryRepository.findAllByOrderByLevelAscSortOrderAscIdAsc());
    }

    /** 返回树形结构。 */
    @Transactional(readOnly = true)
    public List<KnowledgeDTO.CategoryDTO> tree() {
        List<KnowledgeDTO.CategoryDTO> flat = decorate(categoryRepository.findAllByOrderByLevelAscSortOrderAscIdAsc());
        Map<Long, KnowledgeDTO.CategoryDTO> map = flat.stream()
                .collect(Collectors.toMap(KnowledgeDTO.CategoryDTO::getId, d -> d, (a, b) -> a, LinkedHashMap::new));
        List<KnowledgeDTO.CategoryDTO> roots = new ArrayList<>();
        for (KnowledgeDTO.CategoryDTO d : flat) {
            if (d.getParentId() == null) {
                roots.add(d);
            } else {
                KnowledgeDTO.CategoryDTO parent = map.get(d.getParentId());
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(d);
                } else {
                    roots.add(d);
                }
            }
        }
        return roots;
    }

    @Transactional(readOnly = true)
    public KnowledgeDTO.CategoryDTO get(Long id) {
        Category c = require(id);
        KnowledgeDTO.CategoryDTO dto = KnowledgeDTO.CategoryDTO.from(c);
        dto.setChildCount(categoryRepository.countByParentId(id));
        dto.setTotalItemCount(itemRepository.countByCategoryIdAndDeleted(id, 0));
        return dto;
    }

    /** 取某分类及其全部子分类的 id（用于按分类检索时含子树）。 */
    @Transactional(readOnly = true)
    public List<Long> selfAndDescendantIds(Long categoryId) {
        if (categoryId == null) {
            return List.of();
        }
        Category c = require(categoryId);
        List<Long> ids = new ArrayList<>();
        ids.add(c.getId());
        categoryRepository.findSubtree(c.getPath() == null ? "/" + c.getId() + "/" : c.getPath())
                .forEach(sub -> {
                    if (!ids.contains(sub.getId())) {
                        ids.add(sub.getId());
                    }
                });
        return ids;
    }

    @Transactional
    public KnowledgeDTO.CategoryDTO create(KnowledgeDTO.CategorySaveRequest req) {
        String code = StringUtils.hasText(req.getCode()) ? req.getCode() : generateCode(req.getName());
        if (categoryRepository.existsByCode(code)) {
            throw BizException.conflict("分类编码已存在：" + code);
        }
        Long parentId = req.getParentId();
        if (parentId != null && categoryRepository.existsByNameAndParentId(req.getName(), parentId)) {
            throw BizException.conflict("同级下已存在同名分类");
        }

        Category c = new Category();
        c.setName(req.getName());
        c.setCode(code);
        c.setParentId(parentId);
        c.setIcon(req.getIcon());
        c.setDescription(req.getDescription());
        c.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        c.setBuiltin(0);
        c.setItemCount(0);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());

        c.setLevel(parentId == null ? 1 : require(parentId).getLevel() + 1);
        categoryRepository.save(c);
        c.setPath(parentId == null ? "/" + c.getId() + "/" : require(parentId).getPath() + c.getId() + "/");
        categoryRepository.save(c);

        auditService.record("CREATE", "CATEGORY", c.getId(), "新增分类：" + c.getName());
        return KnowledgeDTO.CategoryDTO.from(c);
    }

    @Transactional
    public KnowledgeDTO.CategoryDTO update(Long id, KnowledgeDTO.CategorySaveRequest req) {
        Category c = require(id);
        if (StringUtils.hasText(req.getCode()) && !c.getCode().equals(req.getCode())) {
            if (categoryRepository.existsByCode(req.getCode())) {
                throw BizException.conflict("分类编码已存在：" + req.getCode());
            }
            c.setCode(req.getCode());
        }
        boolean parentChanged = !Objects.equals(c.getParentId(), req.getParentId());
        if (parentChanged && req.getParentId() != null) {
            if (req.getParentId().equals(id)) {
                throw BizException.badRequest("父分类不能是自身");
            }
            Category newParent = require(req.getParentId());
            if (newParent.getPath() != null && c.getPath() != null && newParent.getPath().startsWith(c.getPath())) {
                throw BizException.badRequest("不能把分类移动到自己的子分类下，会产生环");
            }
        }

        c.setName(req.getName());
        c.setIcon(req.getIcon());
        c.setDescription(req.getDescription());
        if (req.getSortOrder() != null) {
            c.setSortOrder(req.getSortOrder());
        }
        c.setParentId(req.getParentId());
        c.setUpdatedAt(LocalDateTime.now());

        if (parentChanged) {
            c.setLevel(req.getParentId() == null ? 1 : require(req.getParentId()).getLevel() + 1);
            categoryRepository.save(c);
            c.setPath(req.getParentId() == null
                    ? "/" + c.getId() + "/"
                    : require(req.getParentId()).getPath() + c.getId() + "/");
            categoryRepository.save(c);
            refreshDescendants(c);
        } else {
            categoryRepository.save(c);
        }

        auditService.record("UPDATE", "CATEGORY", id, "更新分类：" + c.getName());
        return KnowledgeDTO.CategoryDTO.from(c);
    }

    @Transactional
    public void delete(Long id) {
        Category c = require(id);
        if (categoryRepository.countByParentId(id) > 0) {
            throw BizException.badRequest("该分类下仍有子分类，无法删除");
        }
        long items = itemRepository.countByCategoryIdAndDeleted(id, 0);
        if (items > 0) {
            throw BizException.badRequest("该分类下仍有 %d 条知识条目，请先移动或删除".formatted(items));
        }
        categoryRepository.delete(c);
        auditService.record("DELETE", "CATEGORY", id, "删除分类：" + c.getName());
    }

    /** 重新统计各分类的条目数（维护冗余计数）。 */
    @Transactional
    public void recalculateCounts() {
        categoryRepository.recalculateItemCounts();
    }

    // ------------------------------------------------------------------

    public Category require(Long id) {
        return categoryRepository.findById(id).orElseThrow(() -> BizException.notFound("分类"));
    }

    /** 批量取分类名，供列表页填充。 */
    @Transactional(readOnly = true)
    public Map<Long, Category> mapByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return categoryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));
    }

    private List<KnowledgeDTO.CategoryDTO> decorate(List<Category> categories) {
        Map<Long, Long> childCounts = categories.stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(Category::getParentId, Collectors.counting()));
        // 含子树的条目总数：用物化路径前缀匹配累计
        Map<Long, Long> subtreeCounts = new HashMap<>();
        for (Category c : categories) {
            subtreeCounts.put(c.getId(), (long) (c.getItemCount() == null ? 0 : c.getItemCount()));
        }
        for (Category c : categories) {
            if (c.getPath() == null) {
                continue;
            }
            for (Category other : categories) {
                if (!other.getId().equals(c.getId()) && other.getPath() != null
                        && other.getPath().startsWith(c.getPath())) {
                    subtreeCounts.merge(c.getId(),
                            (long) (other.getItemCount() == null ? 0 : other.getItemCount()), Long::sum);
                }
            }
        }

        return categories.stream().map(c -> {
            KnowledgeDTO.CategoryDTO d = KnowledgeDTO.CategoryDTO.from(c);
            d.setChildCount(childCounts.getOrDefault(c.getId(), 0L));
            d.setTotalItemCount(subtreeCounts.getOrDefault(c.getId(), 0L));
            return d;
        }).toList();
    }

    private void refreshDescendants(Category root) {
        for (Category child : categoryRepository.findByParentIdOrderBySortOrderAscIdAsc(root.getId())) {
            child.setLevel(root.getLevel() + 1);
            child.setPath(root.getPath() + child.getId() + "/");
            categoryRepository.save(child);
            refreshDescendants(child);
        }
    }

    /** 由中文名称生成一个稳定的编码：取拼音首字母不现实，这里用「cat_序号」形式。 */
    private String generateCode(String name) {
        String base = "cat_" + Math.abs(name.hashCode() % 100000);
        String code = base;
        int i = 1;
        while (categoryRepository.existsByCode(code)) {
            code = base + "_" + (i++);
        }
        return code;
    }
}
