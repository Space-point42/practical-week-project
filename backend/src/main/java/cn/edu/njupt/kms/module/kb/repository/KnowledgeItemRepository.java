package cn.edu.njupt.kms.module.kb.repository;

import cn.edu.njupt.kms.module.kb.entity.KnowledgeItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 知识条目仓储。
 *
 * <p>三条查询路径：</p>
 * <ol>
 *   <li>JPA Criteria（{@link JpaSpecificationExecutor}）—— 高级检索的动态条件组合；</li>
 *   <li>MySQL 全文索引（ngram 解析器）—— 全文检索的召回通道；</li>
 *   <li>普通派生查询 —— 列表页与统计。</li>
 * </ol>
 */
public interface KnowledgeItemRepository
        extends JpaRepository<KnowledgeItem, Long>, JpaSpecificationExecutor<KnowledgeItem> {

    /**
     * 全文检索召回：返回命中的条目 id 与 MySQL 相关性得分。
     *
     * <p>使用 ngram 解析器建立的全文索引，中文按 2 元切分，
     * 因此“软件测试”这类查询也能命中。</p>
     */
    @Query(value = """
            SELECT i.id AS id,
                   MATCH(i.title, i.summary, i.plain_text) AGAINST (:kw IN NATURAL LANGUAGE MODE) AS score
            FROM kb_item i
            WHERE i.deleted = 0
              AND (:status = '' OR i.status = :status)
              AND MATCH(i.title, i.summary, i.plain_text) AGAINST (:kw IN NATURAL LANGUAGE MODE)
            ORDER BY score DESC, i.view_count DESC
            """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM kb_item i
                    WHERE i.deleted = 0
                      AND (:status = '' OR i.status = :status)
                      AND MATCH(i.title, i.summary, i.plain_text) AGAINST (:kw IN NATURAL LANGUAGE MODE)
                    """,
            nativeQuery = true)
    Page<Object[]> fulltextSearch(@Param("kw") String keyword,
                                  @Param("status") String status,
                                  Pageable pageable);

    /** 与上同，但限定在若干分类内（分类子树检索）。 */
    @Query(value = """
            SELECT i.id AS id,
                   MATCH(i.title, i.summary, i.plain_text) AGAINST (:kw IN NATURAL LANGUAGE MODE) AS score
            FROM kb_item i
            WHERE i.deleted = 0
              AND (:status = '' OR i.status = :status)
              AND i.category_id IN (:categoryIds)
              AND MATCH(i.title, i.summary, i.plain_text) AGAINST (:kw IN NATURAL LANGUAGE MODE)
            ORDER BY score DESC, i.view_count DESC
            """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM kb_item i
                    WHERE i.deleted = 0
                      AND (:status = '' OR i.status = :status)
                      AND i.category_id IN (:categoryIds)
                      AND MATCH(i.title, i.summary, i.plain_text) AGAINST (:kw IN NATURAL LANGUAGE MODE)
                    """,
            nativeQuery = true)
    Page<Object[]> fulltextSearchInCategories(@Param("kw") String keyword,
                                              @Param("status") String status,
                                              @Param("categoryIds") Collection<Long> categoryIds,
                                              Pageable pageable);

    // ---------------- 派生查询 ----------------

    List<KnowledgeItem> findByIdInAndDeleted(Collection<Long> ids, Integer deleted);

    Page<KnowledgeItem> findByStatusAndDeleted(String status, Integer deleted, Pageable pageable);

    long countByDeleted(Integer deleted);

    long countByStatusAndDeleted(String status, Integer deleted);

    long countByCategoryIdAndDeleted(Long categoryId, Integer deleted);

    long countByAssetTypeAndDeleted(String assetType, Integer deleted);

    long countByAuthorIdAndDeleted(Long authorId, Integer deleted);

    long countByPublishedAtAfterAndDeleted(LocalDateTime after, Integer deleted);

    /** 某分类（含子分类）下已发布的条目。 */
    @Query("""
            select i from KnowledgeItem i
            where i.deleted = 0 and i.status = 'PUBLISHED' and i.categoryId in :categoryIds
            order by i.publishedAt desc
            """)
    Page<KnowledgeItem> findPublishedInCategories(@Param("categoryIds") Collection<Long> categoryIds,
                                                  Pageable pageable);

    /** 浏览最多的条目（首页推荐）。 */
    @Query("""
            select i from KnowledgeItem i
            where i.deleted = 0 and i.status = 'PUBLISHED'
            order by i.viewCount desc, i.publishedAt desc
            """)
    List<KnowledgeItem> findHot(Pageable pageable);

    /** 最近更新的条目。 */
    @Query("""
            select i from KnowledgeItem i
            where i.deleted = 0 and i.status = 'PUBLISHED'
            order by i.updatedAt desc
            """)
    List<KnowledgeItem> findRecent(Pageable pageable);

    /** 归档列表。 */
    @Query("""
            select i from KnowledgeItem i
            where i.deleted = 0 and i.status = 'ARCHIVED'
              and (:kw = '' or i.title like concat('%', :kw, '%'))
            order by i.archivedAt desc
            """)
    Page<KnowledgeItem> findArchived(@Param("kw") String keyword, Pageable pageable);

    @Query("select i.id, i.title, i.assetType, i.status, i.updatedAt from KnowledgeItem i where i.deleted = 0")
    List<Object[]> briefAll();

    @Query("select i.assetType, count(i) from KnowledgeItem i where i.deleted = 0 group by i.assetType")
    List<Object[]> countGroupByAssetType();

    @Query("select i.status, count(i) from KnowledgeItem i where i.deleted = 0 group by i.status")
    List<Object[]> countGroupByStatus();

    @Query("select i.categoryId, count(i) from KnowledgeItem i where i.deleted = 0 group by i.categoryId")
    List<Object[]> countGroupByCategory();

    @Modifying
    @Query("update KnowledgeItem i set i.viewCount = i.viewCount + 1 where i.id = :id")
    void incrementViewCount(@Param("id") Long id);

    @Modifying
    @Query("update KnowledgeItem i set i.downloadCount = i.downloadCount + 1 where i.id = :id")
    void incrementDownloadCount(@Param("id") Long id);

    @Modifying
    @Query("update KnowledgeItem i set i.likeCount = i.likeCount + :delta where i.id = :id")
    void addLikeCount(@Param("id") Long id, @Param("delta") int delta);

    /** 供全文检索在无关键词时的兜底统计：全部可见条目。 */
    @Query("select count(i) from KnowledgeItem i where i.deleted = 0 and (:status = '' or i.status = :status)")
    long countVisible(@Param("status") String status);
}
