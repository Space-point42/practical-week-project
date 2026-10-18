package cn.edu.njupt.kms.module.kb.repository;

import cn.edu.njupt.kms.module.kb.entity.ItemConceptLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ItemConceptLinkRepository extends JpaRepository<ItemConceptLink, ItemConceptLink.Key> {

    List<ItemConceptLink> findByItemId(Long itemId);

    List<ItemConceptLink> findByConceptId(Long conceptId);

    List<ItemConceptLink> findByItemIdIn(Collection<Long> itemIds);

    long countByConceptId(Long conceptId);

    @Query("select l.conceptId, count(l) from ItemConceptLink l group by l.conceptId")
    List<Object[]> countGroupByConcept();

    /** 至少被一条知识条目引用的概念数（本体覆盖率分子）。 */
    @Query("select count(distinct l.conceptId) from ItemConceptLink l")
    long countDistinctConcepts();

    @Query("select distinct l.itemId from ItemConceptLink l where l.conceptId in :conceptIds")
    List<Long> findItemIdsByConceptIds(@Param("conceptIds") Collection<Long> conceptIds);

    @Modifying
    @Query("delete from ItemConceptLink l where l.itemId = :itemId")
    void deleteByItemId(@Param("itemId") Long itemId);

    @Modifying
    @Query("delete from ItemConceptLink l where l.conceptId = :conceptId")
    void deleteByConceptId(@Param("conceptId") Long conceptId);
}
