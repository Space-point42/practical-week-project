package cn.edu.njupt.kms.module.rag.repository;

import cn.edu.njupt.kms.module.rag.entity.RagSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagSessionRepository extends JpaRepository<RagSession, Long> {

    List<RagSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<RagSession> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}
