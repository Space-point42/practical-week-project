package cn.edu.njupt.kms.module.rag.repository;

import cn.edu.njupt.kms.module.rag.entity.RagChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RagChunkRepository extends JpaRepository<RagChunk, Long> {

    List<RagChunk> findByItemIdOrderByChunkIndexAsc(Long itemId);

    long countByItemId(Long itemId);

    @Modifying
    @Query("delete from RagChunk c where c.itemId = :itemId")
    void deleteByItemId(@Param("itemId") Long itemId);

    @Modifying
    @Query("delete from RagChunk c where c.itemId in :itemIds")
    void deleteByItemIds(@Param("itemIds") Collection<Long> itemIds);

    /** 关键字通道：MySQL 全文索引在分块表上召回（限定条目范围）。 */
    @Query(value = """
            SELECT c.id,
                   MATCH(c.content) AGAINST (:kw IN NATURAL LANGUAGE MODE) AS score
            FROM rag_chunk c
            WHERE c.item_id IN (:itemIds)
              AND MATCH(c.content) AGAINST (:kw IN NATURAL LANGUAGE MODE)
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> keywordSearch(@Param("kw") String keyword,
                                 @Param("itemIds") Collection<Long> itemIds,
                                 @Param("limit") int limit);

    /** 关键字通道：全库召回。 */
    @Query(value = """
            SELECT c.id,
                   MATCH(c.content) AGAINST (:kw IN NATURAL LANGUAGE MODE) AS score
            FROM rag_chunk c
            WHERE MATCH(c.content) AGAINST (:kw IN NATURAL LANGUAGE MODE)
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> keywordSearchAll(@Param("kw") String keyword, @Param("limit") int limit);

    /** 向量通道：全库取出带向量的分块做内存比对。 */
    @Query("select c from RagChunk c where c.embedding is not null")
    List<RagChunk> findAllWithEmbedding();

    /** 向量通道：限定条目范围内取出带向量的分块。 */
    @Query("select c from RagChunk c where c.embedding is not null and c.itemId in :itemIds")
    List<RagChunk> findWithEmbeddingIn(@Param("itemIds") Collection<Long> itemIds);

    @Query("select count(c) from RagChunk c where c.embedding is not null")
    long countWithEmbedding();

    @Query("select distinct c.embedModel from RagChunk c where c.embedModel is not null")
    List<String> listEmbedModels();

    @Query("select count(c) from RagChunk c")
    long countAll();
}
