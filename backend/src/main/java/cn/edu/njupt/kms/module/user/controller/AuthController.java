package cn.edu.njupt.kms.module.user.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.module.user.dto.AuthDTO;
import cn.edu.njupt.kms.module.user.dto.UserDTO;
import cn.edu.njupt.kms.module.user.service.UserService;
import cn.edu.njupt.kms.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 认证接口：登录、注册、当前用户、改密。 */
@Tag(name = "01 认证", description = "登录 / 注册 / 当前用户")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @Operation(summary = "登录并获取 JWT")
    @PostMapping("/login")
    public ApiResponse<AuthDTO.LoginResponse> login(@Valid @RequestBody AuthDTO.LoginRequest req) {
        return ApiResponse.ok("登录成功", userService.login(req));
    }

    @Operation(summary = "注册（默认浏览者角色）")
    @PostMapping("/register")
    public ApiResponse<UserDTO> register(@Valid @RequestBody AuthDTO.RegisterRequest req) {
        return ApiResponse.ok("注册成功", userService.register(req));
    }

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/me")
    public ApiResponse<UserDTO> me() {
        return ApiResponse.ok(userService.getDTO(SecurityUtils.currentUser().getId()));
    }

    @Operation(summary = "修改当前用户口令")
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody AuthDTO.ChangePasswordRequest req) {
        userService.changePassword(SecurityUtils.currentUser().getId(), req);
        return ApiResponse.ok("口令已修改", null);
    }

    @Operation(summary = "更新当前用户资料")
    @PutMapping("/profile")
    public ApiResponse<UserDTO> updateProfile(@RequestBody AuthDTO.ProfileUpdateRequest req) {
        return ApiResponse.ok(userService.updateProfile(SecurityUtils.currentUser().getId(), req));
    }
}
