package cn.edu.njupt.kms.module.user.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.common.PageResult;
import cn.edu.njupt.kms.module.user.dto.UserDTO;
import cn.edu.njupt.kms.module.user.dto.UserSaveRequest;
import cn.edu.njupt.kms.module.user.entity.Role;
import cn.edu.njupt.kms.module.user.service.UserService;
import cn.edu.njupt.kms.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 用户管理接口（仅管理员）。 */
@Tag(name = "02 用户管理", description = "用户的增删改查与角色分配")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    @Operation(summary = "分页查询用户")
    @GetMapping
    public ApiResponse<PageResult<UserDTO>> page(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Integer status,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(userService.page(keyword, status, page, size));
    }

    @Operation(summary = "查询单个用户")
    @GetMapping("/{id}")
    public ApiResponse<UserDTO> get(@PathVariable Long id) {
        return ApiResponse.ok(userService.getDTO(id));
    }

    @Operation(summary = "创建用户")
    @PostMapping
    public ApiResponse<UserDTO> create(@Valid @RequestBody UserSaveRequest req) {
        return ApiResponse.ok("创建成功", userService.create(req));
    }

    @Operation(summary = "更新用户")
    @PutMapping("/{id}")
    public ApiResponse<UserDTO> update(@PathVariable Long id, @Valid @RequestBody UserSaveRequest req) {
        return ApiResponse.ok("更新成功", userService.update(id, req));
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.delete(id, SecurityUtils.currentUser().getId());
        return ApiResponse.ok("删除成功", null);
    }

    @Operation(summary = "角色字典")
    @GetMapping("/roles")
    public ApiResponse<List<Map<String, Object>>> roles() {
        return ApiResponse.ok(userService.listRoles().stream()
                .map(r -> Map.<String, Object>of(
                        "code", r.getCode(),
                        "name", r.getName(),
                        "description", r.getDescription() == null ? "" : r.getDescription()))
                .toList());
    }

    @Operation(summary = "角色编码常量说明")
    @GetMapping("/role-codes")
    public ApiResponse<List<String>> roleCodes() {
        return ApiResponse.ok(List.of(Role.ADMIN, Role.EDITOR, Role.VIEWER));
    }
}
