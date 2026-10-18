package cn.edu.njupt.kms.module.user.repository;

import cn.edu.njupt.kms.module.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    @Query("""
            select u from User u
            where (:keyword is null or :keyword = ''
                   or lower(u.username) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(u.realName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(u.email, '')) like lower(concat('%', :keyword, '%')))
              and (:status is null or u.status = :status)
            """)
    Page<User> search(@Param("keyword") String keyword, @Param("status") Integer status, Pageable pageable);
}
