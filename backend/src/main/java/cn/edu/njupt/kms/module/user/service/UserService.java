package cn.edu.njupt.kms.module.user.service;

import cn.edu.njupt.kms.common.BizException;
import cn.edu.njupt.kms.common.PageResult;
import cn.edu.njupt.kms.module.audit.service.AuditService;
import cn.edu.njupt.kms.module.user.dto.AuthDTO;
import cn.edu.njupt.kms.module.user.dto.UserDTO;
import cn.edu.njupt.kms.module.user.dto.UserSaveRequest;
import cn.edu.njupt.kms.module.user.entity.Role;
import cn.edu.njupt.kms.module.user.entity.User;
import cn.edu.njupt.kms.module.user.repository.RoleRepository;
import cn.edu.njupt.kms.module.user.repository.UserRepository;
import cn.edu.njupt.kms.security.JwtService;
import cn.edu.njupt.kms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 用户与认证业务逻辑。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    // ------------------------------------------------------------------
    // 认证
    // ------------------------------------------------------------------

    /** 校验账号口令并签发令牌。 */
    @Transactional
    public AuthDTO.LoginResponse login(AuthDTO.LoginRequest req) {
        User user = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> new BizException(cn.edu.njupt.kms.common.ErrorCode.UNAUTHORIZED, "用户名或密码错误"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BizException(cn.edu.njupt.kms.common.ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw BizException.forbidden("账号已被停用，请联系管理员");
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        UserPrincipal principal = UserPrincipal.from(user);
        String token = jwtService.generate(principal);

        AuthDTO.LoginResponse res = new AuthDTO.LoginResponse();
        res.setToken(token);
        res.setExpiresIn(jwtService.getExpireSeconds());
        res.setUser(UserDTO.from(user));

        auditService.record("LOGIN", "USER", user.getId(), "用户登录：" + user.getUsername());
        return res;
    }

    /** 开放注册：默认授予 VIEWER 角色。 */
    @Transactional
    public UserDTO register(AuthDTO.RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw BizException.conflict("用户名已存在");
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setPassword(passwordEncoder.encode(req.getPassword()));
        u.setRealName(StringUtils.hasText(req.getRealName()) ? req.getRealName() : req.getUsername());
        u.setEmail(req.getEmail());
        u.setDepartment(req.getDepartment());
        u.setStatus(1);
        u.setRoles(new LinkedHashSet<>(Set.of(requireRole(Role.VIEWER))));
        userRepository.save(u);

        auditService.record("REGISTER", "USER", u.getId(), "新用户注册：" + u.getUsername());
        return UserDTO.from(u);
    }

    @Transactional
    public void changePassword(Long userId, AuthDTO.ChangePasswordRequest req) {
        User u = get(userId);
        if (!passwordEncoder.matches(req.getOldPassword(), u.getPassword())) {
            throw BizException.badRequest("原密码不正确");
        }
        u.setPassword(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(u);
        auditService.record("CHANGE_PASSWORD", "USER", userId, "修改口令");
    }

    @Transactional
    public UserDTO updateProfile(Long userId, AuthDTO.ProfileUpdateRequest req) {
        User u = get(userId);
        if (StringUtils.hasText(req.getRealName())) {
            u.setRealName(req.getRealName());
        }
        u.setEmail(req.getEmail());
        u.setAvatar(req.getAvatar());
        u.setDepartment(req.getDepartment());
        userRepository.save(u);
        return UserDTO.from(u);
    }

    // ------------------------------------------------------------------
    // 管理端
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResult<UserDTO> page(String keyword, Integer status, int page, int size) {
        Page<User> p = userRepository.search(keyword, status,
                PageRequest.of(Math.max(page - 1, 0), size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResult.of(p, UserDTO::from);
    }

    @Transactional(readOnly = true)
    public UserDTO getDTO(Long id) {
        return UserDTO.from(get(id));
    }

    @Transactional
    public UserDTO create(UserSaveRequest req) {
        if (!StringUtils.hasText(req.getUsername())) {
            throw BizException.badRequest("用户名不能为空");
        }
        if (!StringUtils.hasText(req.getPassword())) {
            throw BizException.badRequest("密码不能为空");
        }
        if (userRepository.existsByUsername(req.getUsername())) {
            throw BizException.conflict("用户名已存在");
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setPassword(passwordEncoder.encode(req.getPassword()));
        u.setRealName(req.getRealName());
        u.setEmail(req.getEmail());
        u.setDepartment(req.getDepartment());
        u.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        u.setRoles(resolveRoles(req.getRoles(), Role.VIEWER));
        userRepository.save(u);
        auditService.record("CREATE", "USER", u.getId(), "创建用户：" + u.getUsername());
        return UserDTO.from(u);
    }

    @Transactional
    public UserDTO update(Long id, UserSaveRequest req) {
        User u = get(id);
        u.setRealName(req.getRealName());
        u.setEmail(req.getEmail());
        u.setDepartment(req.getDepartment());
        if (StringUtils.hasText(req.getAvatar())) {
            u.setAvatar(req.getAvatar());
        }
        if (req.getStatus() != null) {
            u.setStatus(req.getStatus());
        }
        // 留空表示不修改口令
        if (StringUtils.hasText(req.getPassword())) {
            u.setPassword(passwordEncoder.encode(req.getPassword()));
        }
        if (req.getRoles() != null && !req.getRoles().isEmpty()) {
            u.setRoles(resolveRoles(req.getRoles(), Role.VIEWER));
        }
        userRepository.save(u);
        auditService.record("UPDATE", "USER", id, "更新用户：" + u.getUsername());
        return UserDTO.from(u);
    }

    @Transactional
    public void delete(Long id, Long operatorId) {
        if (id.equals(operatorId)) {
            throw BizException.badRequest("不能删除当前登录账号");
        }
        User u = get(id);
        userRepository.delete(u);
        auditService.record("DELETE", "USER", id, "删除用户：" + u.getUsername());
    }

    @Transactional(readOnly = true)
    public List<Role> listRoles() {
        return roleRepository.findAll(Sort.by("sortOrder"));
    }

    // ------------------------------------------------------------------

    public User get(Long id) {
        return userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户"));
    }

    private Role requireRole(String code) {
        return roleRepository.findByCode(code)
                .orElseThrow(() -> new BizException("角色未初始化：" + code));
    }

    private Set<Role> resolveRoles(List<String> codes, String defaultCode) {
        List<String> use = (codes == null || codes.isEmpty()) ? List.of(defaultCode) : codes;
        Set<Role> roles = new LinkedHashSet<>();
        for (String c : use) {
            roles.add(requireRole(c));
        }
        return roles;
    }
}
