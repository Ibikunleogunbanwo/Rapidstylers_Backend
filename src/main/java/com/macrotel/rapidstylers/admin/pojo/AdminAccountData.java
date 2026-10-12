package com.macrotel.rapidstylers.admin.pojo;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminAccountData {
    @NotEmpty(message = "Email Address cannot be empty")
    @Email(message = "Enter a valid email address")
    private String email;

    @NotEmpty(message = "Password cannot be empty")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    /** ADMIN (an owner, the default when left out) or MANAGER. */
    @Pattern(regexp = "(?i)ADMIN|MANAGER", message = "Role must be ADMIN or MANAGER")
    private String role;
}