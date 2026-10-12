package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.customer.pojo.UpdateData;
import com.macrotel.rapidstylers.customer.pojo.UserData;
import com.macrotel.rapidstylers.styler.pojo.StylerBusinessInfoData;
import com.macrotel.rapidstylers.styler.pojo.StylerData;
import com.macrotel.rapidstylers.styler.pojo.StylerPersonalInfoData;
import javax.validation.Validation;
import javax.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared profile rules (QA P-21, P-22): phone, city, province and postal
 * code, for stylist sign-up and updates and for customer profiles. Customer
 * fields stay optional but are checked when given.
 */
class ProfileFieldRulesTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // ── Phone (NANP) ──────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"5875551234", "15875551234", "(587) 555-1234", "+1 587 555 1234", "587.555.1234", "2042345678"})
    void canadianPhonesAreAccepted(String phone) {
        StylerPersonalInfoData data = new StylerPersonalInfoData();
        data.setPhoneNumber(phone);
        assertTrue(validator.validateProperty(data, "phoneNumber").isEmpty(), phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"58755512345", "0000000000", "1234567890", "5871234567", "92 300 1234567",
            "587555123", "25875551234", "587555123a", "1587"})
    void wrongPhonesAreRefused(String phone) {
        StylerPersonalInfoData personal = new StylerPersonalInfoData();
        personal.setPhoneNumber(phone);
        assertFalse(validator.validateProperty(personal, "phoneNumber").isEmpty(), phone);
        StylerData signUp = new StylerData();
        signUp.setPhoneNumber(phone);
        assertFalse(validator.validateProperty(signUp, "phoneNumber").isEmpty(), phone);
        UpdateData customer = new UpdateData();
        customer.setPhoneNumber(phone);
        assertFalse(validator.validateProperty(customer, "phoneNumber").isEmpty(), phone);
    }

    @Test
    void phoneFormattingIsStrippedBeforeSaving() {
        StylerPersonalInfoData data = new StylerPersonalInfoData();
        data.setPhoneNumber(" +1 (587) 555-1234 ");
        assertEquals("15875551234", data.getPhoneNumber());
    }

    // ── City ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"Calgary", "St. John's", "Saint-Jérôme", "Trois-Rivières", " Red Deer "})
    void realCitiesAreAccepted(String city) {
        StylerBusinessInfoData data = new StylerBusinessInfoData();
        data.setCity(city);
        assertTrue(validator.validateProperty(data, "city").isEmpty(), city);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Calgary1", "<b>x</b>", "-Calgary", "@@", "   "})
    void notACityIsRefused(String city) {
        StylerBusinessInfoData business = new StylerBusinessInfoData();
        business.setCity(city);
        assertFalse(validator.validateProperty(business, "city").isEmpty(), city);
    }

    @Test
    void signUpCityIsOptionalButCheckedWhenGiven() {
        StylerData data = new StylerData();
        data.setCity("  ");
        assertNull(data.getCity());
        assertTrue(validator.validateProperty(data, "city").isEmpty());
        data.setCity("Calgary2");
        assertFalse(validator.validateProperty(data, "city").isEmpty());
    }

    // ── Province ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"Alberta", "alberta", "AB", "ab", "Québec", "QC", "Newfoundland and Labrador",
            "Yukon", "NU", " Ontario "})
    void provincesAndCodesAreAccepted(String province) {
        StylerBusinessInfoData data = new StylerBusinessInfoData();
        data.setProvince(province);
        assertTrue(validator.validateProperty(data, "province").isEmpty(), province);
        StylerData signUp = new StylerData();
        signUp.setBusinessProvince(province);
        assertTrue(validator.validateProperty(signUp, "businessProvince").isEmpty(), province);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lagos", "California", "CA", "Albertaa", "XX"})
    void otherPlacesAreRefused(String province) {
        StylerBusinessInfoData data = new StylerBusinessInfoData();
        data.setProvince(province);
        assertFalse(validator.validateProperty(data, "province").isEmpty(), province);
        UpdateData customer = new UpdateData();
        customer.setState(province);
        assertFalse(validator.validateProperty(customer, "state").isEmpty(), province);
    }

    @Test
    void provincesAreSavedByFullName() {
        StylerBusinessInfoData data = new StylerBusinessInfoData();
        data.setProvince("ab");
        assertEquals("Alberta", data.getProvince());
        data.setProvince("Québec");
        assertEquals("Quebec", data.getProvince());
    }

    // ── Postal code ───────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"T2P 1B5", "T2P1B5", "t2p 1b5", "T2P-1B5"})
    void canadianPostalCodesAreAccepted(String postalCode) {
        StylerBusinessInfoData data = new StylerBusinessInfoData();
        data.setPostalCode(postalCode);
        assertTrue(validator.validateProperty(data, "postalCode").isEmpty(), postalCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "T2P 1B", "TTP 1B5", "T2P  1B5", "90210-1234"})
    void otherPostalCodesAreRefused(String postalCode) {
        StylerBusinessInfoData data = new StylerBusinessInfoData();
        data.setPostalCode(postalCode);
        assertFalse(validator.validateProperty(data, "postalCode").isEmpty(), postalCode);
        StylerData signUp = new StylerData();
        signUp.setPostalCode(postalCode);
        assertFalse(validator.validateProperty(signUp, "postalCode").isEmpty(), postalCode);
    }

    @Test
    void postalCodeStaysOptional() {
        StylerBusinessInfoData data = new StylerBusinessInfoData();
        data.setPostalCode(" ");
        assertTrue(validator.validateProperty(data, "postalCode").isEmpty());
    }

    // ── Customers: optional, checked when given ───────────────────────

    @Test
    void customerFieldsLeftBlankAreAccepted() {
        UpdateData update = new UpdateData();
        update.setFirstname("");
        update.setLastname(" ");
        update.setState("");
        update.setPhoneNumber("");
        assertTrue(validator.validate(update).isEmpty());
        assertTrue(validator.validate(new UpdateData()).isEmpty(), "fields left out are fine");
    }

    @ParameterizedTest
    @ValueSource(strings = {"John123", "<script>", "@#$%^"})
    void customerNamesAreCheckedWhenGiven(String name) {
        UpdateData update = new UpdateData();
        update.setFirstname(name);
        assertFalse(validator.validateProperty(update, "firstname").isEmpty(), name);
        UserData signUp = new UserData();
        signUp.setLastname(name);
        assertFalse(validator.validateProperty(signUp, "lastname").isEmpty(), name);
    }

    @Test
    void customerNamesFollowTheStylistRule() {
        UpdateData update = new UpdateData();
        update.setFirstname(" Li ");
        update.setLastname("O’Neil");
        update.setState("on");
        update.setPhoneNumber("(403) 555-0199");
        assertTrue(validator.validate(update).isEmpty());
        assertEquals("Li", update.getFirstname());
        assertEquals("Ontario", update.getState());
        assertEquals("4035550199", update.getPhoneNumber());
    }
}
