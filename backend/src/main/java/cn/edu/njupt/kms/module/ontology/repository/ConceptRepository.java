package cn.edu.njupt.kms.module.ontology.repository;

import cn.edu.njupt.kms.module.ontology.entity.Concept;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConceptRepository extends JpaRepository<Concept, Long> {

    Optional<Concept> findByCode(String code);

    boolean existsByCode(String code);

    List<Concept> findByParentIdOrderBySortOrderAscIdAsc(Long parentId);

    List<Concept> findAllByOrderByLevelAscSortOrderAscIdAsc();

    List<Concept> findByDomainOrderByIdAsc(String domain);

    /** 取某概念及其全部后代（依赖物化路径）。 */
    @Query("select c from Concept c where c.path like concat(:path, '%') order by c.level, c.sortOrder")
    List<Concept> findSubtree(@Param("path") String path);

    @Query("""
            select c from Concept c
            where lower(c.name) like lower(concat('%', :kw, '%'))
               or lower(coalesce(c.enName, '')) like lower(concat('%', :kw, '%'))
               or lower(coalesce(c.aliases, '')) like lower(concat('%', :kw, '%'))
               or lower(c.code) like lower(concat('%', :kw, '%'))
            order by c.level, c.sortOrder
            """)
    List<Concept> search(@Param("kw") String keyword);

    @Query("select distinct c.domain from Concept c where c.domain is not null order by c.domain")
    List<String> listDomains();

    @Query("select coalesce(max(c.sortOrder), 0) from Concept c where c.parentId = :parentId")
    Integer maxSortOrder(@Param("parentId") Long parentId);

    long countByParentId(Long parentId);
}
