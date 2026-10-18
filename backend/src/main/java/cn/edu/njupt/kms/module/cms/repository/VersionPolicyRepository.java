package cn.edu.njupt.kms.module.cms.repository;

import cn.edu.njupt.kms.module.cms.entity.VersionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VersionPolicyRepository extends JpaRepository<VersionPolicy, Long> {

    List<VersionPolicy> findAllByOrderByScopeAscPriorityDescIdAsc();

    List<VersionPolicy> findByScopeAndScopeIdAndEnabledOrderByPriorityDesc(String scope, Long scopeId, Integer enabled);

    List<VersionPolicy> findByScopeAndEnabledOrderByPriorityDesc(String scope, Integer enabled);

    List<VersionPolicy> findByEnabled(Integer enabled);
}
