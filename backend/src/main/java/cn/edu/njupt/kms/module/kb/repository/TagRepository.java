package cn.edu.njupt.kms.module.kb.repository;

import cn.edu.njupt.kms.module.kb.entity.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByName(String name);

    List<Tag> findByNameIn(Collection<String> names);

    List<Tag> findAllByOrderByUseCountDescNameAsc(Pageable pageable);

    @Query("select t from Tag t where lower(t.name) like lower(concat('%', :kw, '%')) order by t.useCount desc")
    List<Tag> search(@Param("kw") String keyword, Pageable pageable);

    @Modifying
    @Query("update Tag t set t.useCount = t.useCount + :delta where t.id = :id")
    void addUseCount(@Param("id") Long id, @Param("delta") int delta);

    /** 清理没有任何条目引用的孤立标签。 */
    @Modifying
    @Query(value = """
            DELETE FROM kb_tag WHERE id NOT IN (SELECT DISTINCT tag_id FROM kb_item_tag)
            """, nativeQuery = true)
    int deleteOrphans();
}
