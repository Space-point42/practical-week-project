package cn.edu.njupt.kms.security;

import cn.edu.njupt.kms.common.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** 获取当前登录用户的静态工具。 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<UserPrincipal> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    /** 取当前用户；未登录时抛 401 业务异常。 */
    public static UserPrincipal currentUser() {
        return currentPrincipal().orElseThrow(
                () -> new BizException(cn.edu.njupt.kms.common.ErrorCode.UNAUTHORIZED, "尚未登录"));
    }

    public static Long currentUserId() {
        return currentPrincipal().map(UserPrincipal::getId).orElse(null);
    }

    public static String currentUsername() {
        return currentPrincipal().map(UserPrincipal::getUsername).orElse(null);
    }

    /** 当前用户是否为管理员。 */
    public static boolean isAdmin() {
        return currentPrincipal().map(UserPrincipal::isAdmin).orElse(false);
    }

    /** 当前用户是否具备编辑权限。 */
    public static boolean canEdit() {
        return currentPrincipal().map(UserPrincipal::canEdit).orElse(false);
    }

    /**
     * 校验：当前用户须为资源所有者或管理员，否则拒绝。
     *
     * @param ownerId 资源创建者 id
     */
    public static void requireOwnerOrAdmin(Long ownerId) {
        UserPrincipal p = currentUser();
        if (p.isAdmin()) {
            return;
        }
        if (ownerId == null || !ownerId.equals(p.getId())) {
            throw BizException.forbidden("只能操作自己创建的内容");
        }
    }
}
