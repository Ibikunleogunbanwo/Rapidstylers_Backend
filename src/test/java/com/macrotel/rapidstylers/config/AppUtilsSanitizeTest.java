package com.macrotel.rapidstylers.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppUtilsSanitizeTest {

    @Test
    void stripsScriptBlocks() {
        assertEquals("", AppUtils.sanitizeText("<script>alert(1)</script>").trim());
        assertEquals("hello", AppUtils.sanitizeText("hello<script>alert(1)</script>"));
    }

    @Test
    void stripsTagsWithEventHandlers() {
        String cleaned = AppUtils.sanitizeText("<img src=x onerror=alert(2)> nice work");
        assertEquals("nice work", cleaned);
    }

    @Test
    void neutralizesJavascriptUris() {
        String cleaned = AppUtils.sanitizeText("<a href=\"javascript:alert(1)\">click</a>");
        assertEquals("click", cleaned);
    }

    @Test
    void preservesBenignText() {
        assertEquals("I <3 this stylist, under $50 total", AppUtils.sanitizeText("I <3 this stylist, under $50 total"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(AppUtils.sanitizeText(null));
        assertEquals("", AppUtils.sanitizeText(""));
    }

    @Test
    void stripsStyleBlocks() {
        assertEquals("text", AppUtils.sanitizeText("<style>body{display:none}</style>text"));
    }
}
