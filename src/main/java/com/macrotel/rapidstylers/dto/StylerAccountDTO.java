package com.macrotel.rapidstylers.dto;

import lombok.Data;

@Data
public class StylerAccountDTO {
    private String firstname;
    private String lastname;
    private String emailAddress;
    private String stylerId;
    private String serviceTypeId;
    private String serviceTypeName;
    private String visibilityStatus;
    private String accountStatus;
    private String profileImageUrl;
    private String businessName;
    private String businessAddress;
    private String businessImageUrl;

}
