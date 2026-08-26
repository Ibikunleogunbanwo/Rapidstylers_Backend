package com.macrotel.rapidstylers.pojo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationConsentValidationTest {
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void customerRegistrationRequiresTermsAcceptance() {
        UserData data = validUser();
        data.setAgreeToTerms(false);
        assertFalse(validator.validate(data).stream()
                .noneMatch(v -> v.getMessage().contains("Terms and Conditions")));

        data.setAgreeToTerms(true);
        assertTrue(validator.validate(data).isEmpty());
    }

    @Test
    void stylistRegistrationRequiresTermsAcceptance() {
        StylerData data = validStyler();
        data.setAgreeToTerms(false);
        assertFalse(validator.validate(data).stream()
                .noneMatch(v -> v.getMessage().contains("Terms and Conditions")));

        data.setAgreeToTerms(true);
        assertTrue(validator.validate(data).isEmpty());
    }

    private UserData validUser() {
        UserData data = new UserData();
        data.setFirstname("Test");
        data.setLastname("Customer");
        data.setEmailAddress("customer@example.com");
        data.setCountry("Canada");
        data.setState("Ontario");
        data.setAddress("123 Main St, Toronto, ON M5V 2T6");
        data.setPhoneNumber("5875551234");
        data.setPassword("Test1234!");
        return data;
    }

    private StylerData validStyler() {
        StylerData data = new StylerData();
        data.setFirstname("Test");
        data.setLastname("Stylist");
        data.setEmailAddress("stylist@example.com");
        data.setCountry("Canada");
        data.setState("Ontario");
        data.setAddress("45 King St W, Toronto, ON");
        data.setPhoneNumber("5875551234");
        data.setPassword("Test1234!");
        data.setIdentificationTypeId("1");
        data.setIdentificationImageUrl("https://example.com/id.jpg");
        data.setProfileImageUrl("https://example.com/profile.jpg");
        data.setBusinessName("Test Styles");
        data.setServiceTypeId("1");
        data.setBusinessAddress("45 King St W, Toronto, ON");
        data.setBusinessProvince("Ontario");
        return data;
    }
}
