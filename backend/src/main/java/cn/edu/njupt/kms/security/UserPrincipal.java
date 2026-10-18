package cn.edu.njupt.kms.security;

import cn.edu.njupt.kms.module.user.entity.Role;
import cn.edu.njupt.kms.module.user.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Spring Security 的登录主体，承载用户标识与角色。
 *
 * <p>权限模型较简单，角色编码直接作为权限字符串（如 {@code ROLE_ADMIN}），
 * 方法级鉴权使用 {@code @PreAuthorize("hasRole('ADMIN')")}。</p>
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final String realName;
    private final boolean enabled;
    private final Set<String> roles;
    private final List<GrantedAuthority> authorities;

    public UserPrincipal(Long id, String username, String password, String realName,
                         boolean enabled, Set<String> roles) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.realName = realName;
        this.enabled = enabled;
        this.roles = roles;
        this.authorities = roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }

    public static UserPrincipal from(User u) {
        return new UserPrincipal(
                u.getId(), u.getUsername(), u.getPassword(), u.getRealName(),
                u.isEnabled(), u.getRoles().stream().map(Role::getCode).collect(java.util.stream.Collectors.toSet()));
    }

    /** 是否为管理员。 */
    public boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }

    /** 是否具备内容编辑权限（管理员或编辑）。 */
    public boolean canEdit() {
        return isAdmin() || roles.contains(Role.EDITOR);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
