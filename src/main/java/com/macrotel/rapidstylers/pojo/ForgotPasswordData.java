package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
@Data
public class ForgotPasswordData {
    @NotEmpty
    @Email(message = "Enter a Valid Email Address")
    private String emailAddress;
    private String password;
    private String confirmPassword;

    public ForgotPasswordData() {
    }
}
