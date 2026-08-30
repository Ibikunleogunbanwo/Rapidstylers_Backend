package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;

@Data
public class AdminAccountData {
    @NotEmpty(message = "Email Address cannot be empty")
    @Email(message = "Enter a valid email address")
    private String email;

    @NotEmpty(message = "Password cannot be empty")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}