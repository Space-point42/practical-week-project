package cn.edu.njupt.kms.module.ontology.repository;

import cn.edu.njupt.kms.module.ontology.entity.Relation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RelationRepository extends JpaRepository<Relation, Long> {

    List<Relation> findBySourceId(Long sourceId);

    List<Relation> findByTargetId(Long targetId);

    /** 与某概念直接相连的全部关系（出边 + 入边）。 */
    @Query("select r from Relation r where r.sourceId = :id or r.targetId = :id")
    List<Relation> findByEitherEnd(@Param("id") Long conceptId);

    /** 只取由给定节点集合内部构成的关系，用于子图过滤。 */
    @Query("select r from Relation r where r.sourceId in :ids and r.targetId in :ids")
    List<Relation> findWithin(@Param("ids") List<Long> ids);

    boolean existsBySourceIdAndTargetIdAndRelationType(Long sourceId, Long targetId, String relationType);

    List<Relation> findByRelationType(String relationType);

    @Modifying
    @Query("delete from Relation r where r.sourceId = :id or r.targetId = :id")
    void deleteByEitherEnd(@Param("id") Long conceptId);

    @Query("select r.relationType, count(r) from Relation r group by r.relationType")
    List<Object[]> countGroupByType();
}
