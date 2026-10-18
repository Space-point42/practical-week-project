package cn.edu.njupt.kms.module.search.repository;

import cn.edu.njupt.kms.module.search.entity.SearchLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {

    /** 热搜词：按出现次数排序。 */
    @Query("""
            select s.keyword, count(s) as c
            from SearchLog s
            where s.createdAt >= :since
              and s.keyword is not null and s.keyword <> ''
            group by s.keyword
            order by c desc
            """)
    List<Object[]> hotKeywords(@Param("since") LocalDateTime since, Pageable pageable);

    /** 零结果检索词：用于发现知识库的内容缺口。 */
    @Query("""
            select s.keyword, count(s) as c
            from SearchLog s
            where s.createdAt >= :since and s.resultCount = 0
              and s.keyword is not null and s.keyword <> ''
            group by s.keyword
            order by c desc
            """)
    List<Object[]> noResultKeywords(@Param("since") LocalDateTime since, Pageable pageable);

    long countByCreatedAtAfter(LocalDateTime since);

    @Query("select coalesce(avg(s.costMs), 0) from SearchLog s where s.createdAt >= :since")
    double averageCost(@Param("since") LocalDateTime since);

    /** 前缀联想取词。 */
    @Query("""
            select distinct s.keyword from SearchLog s
            where s.keyword like concat(:prefix, '%')
            order by s.keyword
            """)
    List<String> findKeywordsStartingWith(@Param("prefix") String prefix, Pageable pageable);
}
