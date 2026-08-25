package com.macrotel.rapidstylers.pojo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailabilityUpdateDataValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private AvailabilityData slot(String day, String start, String end) {
        AvailabilityData slot = new AvailabilityData();
        slot.setDayOfWeek(day);
        slot.setStartTime(start);
        slot.setEndTime(end);
        return slot;
    }

    private Set<String> messages(AvailabilityUpdateData data) {
        return validator.validate(data).stream()
                .map(v -> v.getMessage())
                .collect(Collectors.toSet());
    }

    @Test
    void validSlotsPassBeanValidation() {
        AvailabilityUpdateData data = new AvailabilityUpdateData();
        data.setSlots(List.of(slot("1", "09:00", "17:00"), slot("2", "10:00", "14:00")));
        assertTrue(validator.validate(data).isEmpty());
    }

    @Test
    void outOfRangeDayOfWeekFailsBeanValidation() {
        AvailabilityUpdateData data = new AvailabilityUpdateData();
        data.setSlots(List.of(slot("7", "09:00", "17:00")));
        assertFalse(messages(data).isEmpty(), "dayOfWeek=7 must fail element validation");
        assertTrue(messages(data).stream().anyMatch(m -> m.contains("Day of week")));
    }

    @Test
    void malformedTimeFailsBeanValidation() {
        AvailabilityUpdateData data = new AvailabilityUpdateData();
        data.setSlots(List.of(slot("1", "9am", "17:00")));
        assertFalse(messages(data).isEmpty(), "malformed time must fail element validation");
    }

    @Test
    void nullSlotsFailsBeanValidation() {
        AvailabilityUpdateData data = new AvailabilityUpdateData();
        data.setSlots(null);
        assertFalse(messages(data).isEmpty());
    }
}
