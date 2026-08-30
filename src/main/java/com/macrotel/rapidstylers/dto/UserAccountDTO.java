package com.macrotel.rapidstylers.dto;

import lombok.Data;

@Data
public class UserAccountDTO {
    private String address;
    private String country;
    private String emailAddress;
    private String firstname;
    private String lastname;
    private String dateRegistered;
    private String phoneNumber;
    private String state;
    private String userId;
    private String registrationMethod;
}
