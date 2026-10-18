package cn.edu.njupt.kms.module.audit.repository;

import cn.edu.njupt.kms.module.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findTop20ByOrderByIdDesc();

    List<AuditLog> findByTargetTypeAndTargetIdOrderByIdDesc(String targetType, Long targetId);

    @Query("""
            select a from AuditLog a
            where (:action is null or :action = '' or a.action = :action)
              and (:targetType is null or :targetType = '' or a.targetType = :targetType)
              and (:username is null or :username = '' or a.username like concat('%', :username, '%'))
              and (:from is null or a.createdAt >= :from)
              and (:to is null or a.createdAt <= :to)
            order by a.id desc
            """)
    Page<AuditLog> search(@Param("action") String action,
                          @Param("targetType") String targetType,
                          @Param("username") String username,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          Pageable pageable);
}
