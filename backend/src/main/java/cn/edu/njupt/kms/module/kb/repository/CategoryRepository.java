package cn.edu.njupt.kms.module.kb.repository;

import cn.edu.njupt.kms.module.kb.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByNameAndParentId(String name, Long parentId);

    List<Category> findByParentIdOrderBySortOrderAscIdAsc(Long parentId);

    List<Category> findAllByOrderByLevelAscSortOrderAscIdAsc();

    @Query("select c from Category c where c.path like concat(:path, '%') order by c.level, c.sortOrder")
    List<Category> findSubtree(@Param("path") String path);

    long countByParentId(Long parentId);

    @Modifying
    @Query("update Category c set c.itemCount = c.itemCount + :delta where c.id = :id")
    void addItemCount(@Param("id") Long id, @Param("delta") int delta);

    /** 重新计算全部条目的分类计数（维护冗余字段，管理员可手工触发）。 */
    @Modifying
    @Query(value = """
            UPDATE kb_category c SET c.item_count = (
                SELECT COUNT(*) FROM kb_item i WHERE i.category_id = c.id AND i.deleted = 0
            )
            """, nativeQuery = true)
    void recalculateItemCounts();
}
