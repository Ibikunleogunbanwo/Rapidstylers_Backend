package com.macrotel.rapidstylers.catalog.service;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * What an admin may save as a service category or a blog article. Each check
 * returns the message to show, or null when the value is fine. The website
 * applies the same rules before sending.
 */
final class CatalogRules {

    private CatalogRules() {
    }

    /**
     * A category name: letters (any alphabet), spaces, hyphens, apostrophes,
     * ampersands and periods, starting with a letter. Length is checked
     * separately so the message can say what is wrong.
     */
    private static final Pattern CATEGORY_NAME = Pattern.compile("^\\p{L}[\\p{L}\\p{M} &'’.-]*$");

    static final String DUPLICATE_CATEGORY = "A category with this name already exists";

    /** Trimmed, with inner runs of spaces collapsed; null stays null. */
    static String clean(String value) {
        return value == null ? null : value.trim().replaceAll("\\s{2,}", " ");
    }

    static String categoryNameError(String name) {
        if (name == null || name.length() < 2 || name.length() > 50) {
            return "Category name must be 2 to 50 characters";
        }
        if (!CATEGORY_NAME.matcher(name).matches()) {
            return "Category name may only contain letters, spaces, hyphens, apostrophes and &";
        }
        return null;
    }

    /** Title, content and image URL of an article, checked after sanitising. */
    static String blogError(String title, String content, String imageUrl) {
        if (title == null || title.isBlank()) {
            return "Blog title cannot be empty";
        }
        if (content == null || content.isBlank()) {
            return "Blog content cannot be empty";
        }
        if (imageUrl != null && !imageUrl.isBlank() && !isHttpsUrl(imageUrl.trim())) {
            return "Image URL must be a full https:// link";
        }
        return null;
    }

    static boolean isHttpsUrl(String url) {
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception ex) {
            return false;
        }
    }
}
