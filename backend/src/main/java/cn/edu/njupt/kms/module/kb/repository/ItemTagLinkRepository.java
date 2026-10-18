package cn.edu.njupt.kms.module.kb.repository;

import cn.edu.njupt.kms.module.kb.entity.ItemTagLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ItemTagLinkRepository extends JpaRepository<ItemTagLink, ItemTagLink.Key> {

    List<ItemTagLink> findByItemId(Long itemId);

    List<ItemTagLink> findByItemIdIn(Collection<Long> itemIds);

    List<ItemTagLink> findByTagId(Long tagId);

    @Query("select l.itemId from ItemTagLink l where l.tagId in :tagIds")
    List<Long> findItemIdsByTagIds(@Param("tagIds") Collection<Long> tagIds);

    @Query("select l.tagId, count(l) from ItemTagLink l group by l.tagId order by count(l) desc")
    List<Object[]> countGroupByTag();

    @Modifying
    @Query("delete from ItemTagLink l where l.itemId = :itemId")
    void deleteByItemId(@Param("itemId") Long itemId);
}
