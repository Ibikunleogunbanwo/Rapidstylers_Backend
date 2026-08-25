package com.macrotel.rapidstylers.entity;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.pojo.UserData;
import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "user_accounts")
public class UserEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String firstname;
    private String lastname;
    private String emailAddress;
    private String country;
    private String state;
    private String address;
    private String phoneNumber;
    private String password;
    private String status;
    private String insertedDt;
    private String userId;
    // Saved-stylist notification preferences. Null means enabled for legacy accounts.
    private Boolean notifySavedAvailability = true;
    private Boolean notifySavedPrice = true;
    private Boolean notifySavedVerification = true;

    public UserEntity() {
    }

    public UserEntity(UserData userData){
        AppUtils appUtils = new AppUtils();
        this.firstname = userData.getFirstname();
        this.lastname = userData.getLastname();
        this.emailAddress = userData.getEmailAddress();
        this.country = userData.getCountry();
        this.state = userData.getState();
        this.address = userData.getAddress();
        this.phoneNumber = userData.getPhoneNumber();
        this.password= appUtils.encryptPassword(userData.getPassword());
        this.status = "0";
        this.insertedDt = String.valueOf(LocalDate.now());
    }
}
