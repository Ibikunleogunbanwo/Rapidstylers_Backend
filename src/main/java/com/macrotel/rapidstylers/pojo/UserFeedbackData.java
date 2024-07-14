package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

@Data
public class UserFeedbackData {
    @NotEmpty(message = "Email Address cannot be empty")
    @Email(message = "Enter a valid email address")
    private String emailAddress;
    @NotEmpty(message = "Message cannot be empty")
    private String message;
    @NotEmpty(message = "User Id cannot be empty")
    private String userId;
    private String feedbackType;
}
