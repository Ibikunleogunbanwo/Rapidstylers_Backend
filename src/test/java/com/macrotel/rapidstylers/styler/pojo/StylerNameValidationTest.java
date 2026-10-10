package com.macrotel.rapidstylers.styler.pojo;

import javax.validation.Validation;
import javax.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Professionals sign up under their real names. The sign-up form only checks
 * length, so any name refused here fails at the very last step of sign-up.
 */
class StylerNameValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private boolean nameAccepted(String name) {
        StylerData data = new StylerData();
        data.setFirstname(name);
        data.setLastname(name);
        return validator.validateProperty(data, "firstname").isEmpty()
                && validator.validateProperty(data, "lastname").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ada", "Mary Jane", "Anne-Marie", "O'Neil", "O’Neil", "Chloé",
            "Adébáyọ̀", "Ọlámídé", "St. John", "Nguyễn"})
    void realNamesAreAccepted(String name) {
        assertTrue(nameAccepted(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Al", " Ada", "-Ada", "Ada1", "<b>Ada</b>", "Ada@x",
            "Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void notANameIsRefused(String name) {
        assertFalse(nameAccepted(name), name);
    }
}
