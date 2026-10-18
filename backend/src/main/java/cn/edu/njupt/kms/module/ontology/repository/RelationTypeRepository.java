package cn.edu.njupt.kms.module.ontology.repository;

import cn.edu.njupt.kms.module.ontology.entity.RelationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RelationTypeRepository extends JpaRepository<RelationType, Long> {

    Optional<RelationType> findByCode(String code);

    List<RelationType> findAllByOrderBySortOrderAsc();
}
