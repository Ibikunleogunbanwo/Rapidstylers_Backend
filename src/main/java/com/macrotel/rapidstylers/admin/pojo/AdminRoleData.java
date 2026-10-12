package com.macrotel.rapidstylers.admin.pojo;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import lombok.Data;

/** The new role for an admin account: ADMIN (an owner) or MANAGER. */
@Data
public class AdminRoleData {
    @NotEmpty(message = "Role cannot be empty")
    @Pattern(regexp = "(?i)ADMIN|MANAGER", message = "Role must be ADMIN or MANAGER")
    private String role;
}
