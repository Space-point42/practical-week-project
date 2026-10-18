package cn.edu.njupt.kms.module.user.dto;

import cn.edu.njupt.kms.module.user.entity.Role;
import cn.edu.njupt.kms.module.user.entity.User;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 用户信息视图对象（绝不包含口令散列）。 */
@Data
public class UserDTO {

    private Long id;
    private String username;
    private String realName;
    private String email;
    private String avatar;
    private String department;
    private Integer status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private List<String> roles;

    public static UserDTO from(User u) {
        UserDTO d = new UserDTO();
        d.id = u.getId();
        d.username = u.getUsername();
        d.realName = u.getRealName();
        d.email = u.getEmail();
        d.avatar = u.getAvatar();
        d.department = u.getDepartment();
        d.status = u.getStatus();
        d.lastLoginAt = u.getLastLoginAt();
        d.createdAt = u.getCreatedAt();
        d.roles = u.getRoles().stream().map(Role::getCode).toList();
        return d;
    }
}
