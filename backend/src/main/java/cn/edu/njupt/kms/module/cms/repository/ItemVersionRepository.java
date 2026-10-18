package cn.edu.njupt.kms.module.cms.repository;

import cn.edu.njupt.kms.module.cms.entity.ItemVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ItemVersionRepository extends JpaRepository<ItemVersion, Long> {

    Page<ItemVersion> findByItemIdOrderByVersionNoDesc(Long itemId, Pageable pageable);

    List<ItemVersion> findByItemIdOrderByVersionNoDesc(Long itemId);

    Optional<ItemVersion> findByItemIdAndVersionNo(Long itemId, Integer versionNo);

    Optional<ItemVersion> findFirstByItemIdOrderByVersionNoDesc(Long itemId);

    long countByItemId(Long itemId);

    @Query("select coalesce(max(v.versionNo), 0) from ItemVersion v where v.itemId = :itemId")
    int maxVersionNo(@Param("itemId") Long itemId);

    @Query("select v from ItemVersion v where v.itemId = :itemId order by v.versionNo desc limit :limit")
    List<ItemVersion> findLatest(@Param("itemId") Long itemId, @Param("limit") int limit);

    /** 淘汰最旧的版本，仅保留最新的 keep 条。 */
    @Modifying
    @Query(value = """
            DELETE FROM cms_item_version
            WHERE item_id = :itemId
              AND id NOT IN (
                  SELECT id FROM (
                      SELECT id FROM cms_item_version
                      WHERE item_id = :itemId
                      ORDER BY version_no DESC
                      LIMIT :keep
                  ) t
              )
            """, nativeQuery = true)
    int pruneOldVersions(@Param("itemId") Long itemId, @Param("keep") int keep);

    @Query("select count(v) from ItemVersion v")
    long countAll();

    @Query("""
            select v from ItemVersion v
            where (:keyword = '' or v.title like concat('%', :keyword, '%')
                   or v.changeLog like concat('%', :keyword, '%'))
            order by v.createdAt desc
            """)
    Page<ItemVersion> search(@Param("keyword") String keyword, Pageable pageable);

    /** 最近变更（工作台展示）。 */
    @Query("select v from ItemVersion v order by v.createdAt desc")
    List<ItemVersion> findRecent(Pageable pageable);
}
