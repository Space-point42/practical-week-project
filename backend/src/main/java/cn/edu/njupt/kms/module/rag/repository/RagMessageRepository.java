package cn.edu.njupt.kms.module.rag.repository;

import cn.edu.njupt.kms.module.rag.entity.RagMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RagMessageRepository extends JpaRepository<RagMessage, Long> {

    List<RagMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 取最近 N 轮历史（倒序取出后由调用方反转），用于多轮对话的上下文。 */
    @Query("select m from RagMessage m where m.sessionId = :sessionId order by m.id desc")
    List<RagMessage> findRecent(@Param("sessionId") Long sessionId, Pageable pageable);

    @Modifying
    @Query("delete from RagMessage m where m.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") Long sessionId);

    long countBySessionId(Long sessionId);
}
