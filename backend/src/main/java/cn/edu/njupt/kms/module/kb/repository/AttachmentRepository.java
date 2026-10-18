package cn.edu.njupt.kms.module.kb.repository;

import cn.edu.njupt.kms.module.kb.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByItemIdOrderByIdAsc(Long itemId);

    List<Attachment> findByItemIdIn(Collection<Long> itemIds);

    long countByItemId(Long itemId);

    long countByMediaType(String mediaType);

    @Modifying
    @Query("update Attachment a set a.itemId = :itemId where a.id in :ids")
    void bindToItem(@Param("itemId") Long itemId, @Param("ids") Collection<Long> ids);

    /** 未被任何条目引用的附件（孤儿文件清理）。 */
    @Query("select a from Attachment a where a.itemId is null and a.createdAt < :before")
    List<Attachment> findOrphans(@Param("before") java.time.LocalDateTime before);

    /** 全文检索附件正文，返回附件所属条目 id 及得分。 */
    @Query(value = """
            SELECT DISTINCT a.item_id
            FROM kb_attachment a
            WHERE a.item_id IS NOT NULL
              AND MATCH(a.file_name, a.extracted_text) AGAINST (:kw IN NATURAL LANGUAGE MODE)
            """, nativeQuery = true)
    List<Long> searchItemIdsByFulltext(@Param("kw") String keyword);

    @Query(value = """
            SELECT a.* FROM kb_attachment a
            WHERE a.item_id = :itemId
              AND a.extracted_text IS NOT NULL
            ORDER BY a.id
            """, nativeQuery = true)
    List<Attachment> findWithTextByItem(@Param("itemId") Long itemId);
}
