package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.catalog.service.ServiceAreaService;
import java.text.Normalizer;

/**
 * Tidies profile fields as a request body is read (DTO setters call these), so
 * the validation rules in AppConstants see the value that will be saved:
 * names and places trimmed, phone formatting stripped, provinces by full name.
 * Null stays null, so an optional field left out stays left out.
 */
public final class InputFields {

    private InputFields() {
    }

    public static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** "+1 (587) 555-1234" becomes "15875551234". Anything else (letters) is kept, so the rule still refuses it. */
    public static String phone(String value) {
        return value == null ? null : value.replaceAll("[\\s().+-]", "");
    }

    /** "ab", "Alberta " and "ALBERTA" become "Alberta", "Québec" becomes "Quebec"; an unknown value is only trimmed. */
    public static String province(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        String plain = Normalizer.normalize(trimmed, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String canonical = ServiceAreaService.canonicalProvince(plain);
        return canonical == null ? trimmed : canonical;
    }
}
