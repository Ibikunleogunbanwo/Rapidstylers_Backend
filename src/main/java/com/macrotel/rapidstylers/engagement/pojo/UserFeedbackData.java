package com.macrotel.rapidstylers.engagement.pojo;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class UserFeedbackData {
    @NotEmpty(message = "Email Address cannot be empty")
    @Email(message = "Enter a valid email address")
    private String emailAddress;
    @NotEmpty(message = "Message cannot be empty")
    private String message;
    // userId is derived from the JWT subject by the controller — never from the client.
    private String userId;
    private String feedbackType;
}
