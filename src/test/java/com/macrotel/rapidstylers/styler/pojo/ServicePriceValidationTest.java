package com.macrotel.rapidstylers.styler.pojo;

import java.util.Set;
import java.util.stream.Collectors;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stylist's service price (QA BUG-05): a number above $0 and at most
 * $10,000, on create and on update, with a message that says what is wrong.
 */
class ServicePriceValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private Set<String> createErrors(String price) {
        SubServiceData data = new SubServiceData();
        data.setName("Braids");
        data.setPrice(price);
        return messages(validator.validate(data));
    }

    private Set<String> updateErrors(String price) {
        ServiceUpdateData data = new ServiceUpdateData();
        data.setId(1L);
        data.setName("Braids");
        data.setPrice(price);
        return messages(validator.validate(data));
    }

    private static <T> Set<String> messages(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"45", "45.50", "0.50", "1,250.00", "10000", "10,000.00"})
    void sensiblePricesAreAccepted(String price) {
        assertTrue(createErrors(price).isEmpty(), price);
        assertTrue(updateErrors(price).isEmpty(), price);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc-5", "abc", "-5", "45$", "4 5", "1e3"})
    void notANumberIsRefusedWithAFormatMessage(String price) {
        assertEquals(Set.of(SubServiceData.PRICE_FORMAT_MESSAGE), createErrors(price), price);
        assertEquals(Set.of(SubServiceData.PRICE_FORMAT_MESSAGE), updateErrors(price), price);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "10000.01", "10,001", "999999"})
    void zeroOrAbsurdPricesAreRefusedWithARangeMessage(String price) {
        assertEquals(Set.of(SubServiceData.PRICE_RANGE_MESSAGE), createErrors(price), price);
        assertEquals(Set.of(SubServiceData.PRICE_RANGE_MESSAGE), updateErrors(price), price);
    }
}
