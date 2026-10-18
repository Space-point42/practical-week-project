package cn.edu.njupt.kms.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 管理员创建/更新用户的请求体。 */
@Data
public class UserSaveRequest {

    /** 新建时必填；更新时忽略。 */
    @Size(min = 3, max = 32, message = "用户名长度需在 3~32 之间")
    private String username;

    /** 新建时必填，更新时留空表示不改口令。 */
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 之间")
    private String password;

    @NotBlank(message = "姓名不能为空")
    private String realName;

    private String email;
    private String avatar;
    private String department;

    /** 1=启用 0=停用 */
    private Integer status;

    /** 角色编码列表，如 ["EDITOR"]。 */
    private List<String> roles;
}
