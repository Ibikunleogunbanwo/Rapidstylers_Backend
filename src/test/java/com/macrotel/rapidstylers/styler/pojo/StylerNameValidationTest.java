package com.macrotel.rapidstylers.styler.pojo;

import javax.validation.Validation;
import javax.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Professionals sign up under their real names: 1 to 50 letters (any script),
 * spaces, hyphens, apostrophes and periods, trimmed first. The website checks
 * the same rule at step 1, so a name refused here is refused there too.
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
            "Adébáyọ̀", "Ọlámídé", "St. John", "Nguyễn", "Al", "Li", "J", "李明", " Ada ",
            "Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void realNamesAreAccepted(String name) {
        assertTrue(nameAccepted(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "-Ada", "123", "Ada1", "John123", "@#$%^", "<b>Ada</b>",
            "<script>alert(1)</script>", "Ada@x", "Ada 😀",
            "Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void notANameIsRefused(String name) {
        assertFalse(nameAccepted(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Li", "Ada1"})
    void theProfileUpdateUsesTheSameRule(String name) {
        StylerPersonalInfoData data = new StylerPersonalInfoData();
        data.setFirstname(name);
        assertEquals(nameAccepted(name), validator.validateProperty(data, "firstname").isEmpty(), name);
    }

    @Test
    void namesAreSavedTrimmed() {
        StylerData data = new StylerData();
        data.setFirstname("  Ada ");
        assertEquals("Ada", data.getFirstname());
    }
}
