package com.macrotel.rapidstylers.entity;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.pojo.StylerData;
import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "stylers")
public class StylerEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String firstname;
    private String lastname;
    private String emailAddress;
    private String password;
    private String phoneNumber;
    private String address;
    private String identificationId;
    private String businessName;
    private String serviceTypeId;
    private String country;
    private String province;
    private String businessAddress;
    private String profileImageUrl;
    private String isOnline;
    private String insertedDt;
    private String status;
    private String stylerId;

    public StylerEntity() {
    }
    public StylerEntity(StylerData stylerData) {
        AppUtils appUtils = new AppUtils();
        this.firstname = stylerData.getFirstname();
        this.lastname = stylerData.getLastname();
        this.emailAddress = stylerData.getEmailAddress();
        this.password = appUtils.encryptPassword(stylerData.getPassword());
        this.address = stylerData.getAddress();
        this.identificationId = stylerData.getIdentificationTypeId();
        this.businessName = stylerData.getBusinessName();
        this.serviceTypeId = stylerData.getServiceTypeId();
        this.country = stylerData.getCountry();
        this.province = stylerData.getBusinessProvince();
        this.businessAddress = stylerData.getBusinessAddress();
        this.profileImageUrl = stylerData.getProfileImageUrl();
        this.insertedDt = String.valueOf(LocalDate.now());
        this.stylerId = stylerData.getFirstname().toUpperCase().charAt(0)+stylerData.getLastname().toUpperCase().charAt(0)+appUtils.randomDigit(4);
        this.status = "0";
        this.isOnline = "1";
    }
}
