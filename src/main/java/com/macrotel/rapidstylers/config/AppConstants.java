package com.macrotel.rapidstylers.config;

import java.util.Arrays;
import java.util.List;

public class AppConstants {
    public static final String ERROR_STATUS_CODE = "400";
    public static final String SUCCESS_STATUS_CODE = "200";
    public static final String ERROR_MESSAGE = "Unsuccessful";
    public static final String AMOUNT_VALIDATION_REGEX = "^\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?$|^\\d+(?:\\.\\d+)?$";
    public static final  String SUCCESS_MESSAGE = "Successful";
    public static  final Object EMPTY_DATA = new Object[0];
    public static final String CHARACTER_VALIDATION_REGEX = "^[a-zA-Z ]{3,}(?: [a-zA-Z ]+){0,2}$";
    // A person's name: 1 to 50 characters, starting with a letter in any language,
    // then letters, spaces, hyphens, apostrophes or periods (Li, Anne-Marie, O'Neil, Adébáyọ̀).
    // The website checks the same rule; the DTO setters trim before it is checked.
    public static final String PERSON_NAME_REGEX = "^\\p{L}[\\p{L}\\p{M} '\u2019.-]{0,49}$";
    public static final String PERSON_NAME_MESSAGE = "name must be 1 to 50 letters (spaces, hyphens, apostrophes and periods allowed)";
    // A city: 1 to 60 characters, same alphabet as a name (Saint-Jérôme, St. John's).
    public static final String CITY_REGEX = "^\\p{L}[\\p{L}\\p{M} '\u2019.-]{0,59}$";
    public static final String CITY_MESSAGE = "Enter a valid city name (letters, spaces, hyphens, apostrophes or periods)";
    // A Canadian postal code: T2P 1B5, T2P1B5 or T2P-1B5.
    public static final String POSTAL_CODE_REGEX = "^[A-Za-z]\\d[A-Za-z][ -]?\\d[A-Za-z]\\d$";
    public static final String POSTAL_CODE_MESSAGE = "Enter a valid Canadian postal code, e.g. T2P 1B5";
    // One of the 13 provinces and territories, by full English name or 2-letter code, any case.
    public static final String PROVINCE_REGEX = "(?iu)^(AB|BC|MB|NB|NL|NS|ON|PE|QC|SK|NT|NU|YT|Alberta|British Columbia|Manitoba"
            + "|New Brunswick|Newfoundland and Labrador|Nova Scotia|Ontario|Prince Edward Island|Qu[e\u00e9]bec|Saskatchewan"
            + "|Northwest Territories|Nunavut|Yukon)$";
    public static final String PROVINCE_MESSAGE = "Choose a Canadian province or territory";
    // Prefix for an optional field's rule, where blank means "not given": BLANK_OR + CITY_REGEX.
    public static final String BLANK_OR = "^$|";
    public static final String NUMBER_VALIDATION_REGEX = "^[0-9]+$";
    // Canadian (NANP) phone, formatting already stripped by the DTO setter: 10 digits,
    // or 11 with a leading 1; area code and exchange each start with 2-9.
    public static final String PHONE_NUMBER_VALIDATION_REGEX ="^1?[2-9][0-9]{2}[2-9][0-9]{6}$";
    public static final String PHONE_NUMBER_MESSAGE = "Enter a valid Canadian phone number (10 digits, or 11 with leading 1)";
    // Sub-service price, in dollars: above 0 and at most this.
    public static final int MAX_SERVICE_PRICE = 10000;
    // Password: 8+ chars, at least 1 uppercase, 1 lowercase, 1 digit, 1 special char
    public static final String PASSWORD_PATTERN = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#&()-[{}]:;'?/*~$^+=<>.]).{8,30}$";
    public static final String ALGORITHM = "AES";
    // Legacy encryption fallback key. It is PUBLIC source code, so EncryptionConfig
    // refuses to boot on it unless the active Spring profile is "test" — real
    // environments must set ENCRYPT_KEY (see .env.example: `openssl rand -hex 32`).
    public static final String ENCRYPT_DECRYPT_KEY_FALLBACK = "D0n!T'T&mp3r@w1Th^&()";

    // The value shipped in .env.example for JWT_SECRET. It is PUBLIC source code,
    // so JwtUtil refuses to boot on it — anyone with the repo could otherwise mint
    // admin tokens. Not merely a weak secret: a published one.
    // Keep in sync with JWT_PLACEHOLDER in scripts/check-required-env.sh.
    public static final String JWT_SECRET_PLACEHOLDER = "run_openssl_rand_-hex_64";

    // Professional verification workflow states (StylerEntity.verificationStatus)
    public static final String VERIFICATION_PENDING = "PENDING";
    public static final String VERIFICATION_APPROVED = "APPROVED";
    public static final String VERIFICATION_REJECTED = "REJECTED";
    public static final String VERIFICATION_SUSPENDED = "SUSPENDED";

    // Gallery categories — used by the gallery tabs and the stylist work uploads.
    // Must stay in sync with the frontend CATEGORIES list.
    public static final List<String> GALLERY_CATEGORIES = Arrays.asList(
            "dreadlocks", "buzz cut", "braids", "cornrows", "wigs", "high-top fade",
            "hair dye", "nail tech", "makeup", "eyelash extensions", "natural hair", "locs");

    // Service duration is stored in 15-minute increments.
    public static final int DEFAULT_SERVICE_DURATION_MINUTES = 60;
    public static final int MIN_SERVICE_DURATION_MINUTES = 15;
    public static final int MAX_SERVICE_DURATION_MINUTES = 480;

    // Max portfolio images a stylist can upload (they replace Pexels over time).
    public static final int MAX_STYLER_PORTFOLIO_IMAGES = 30;

    // Runtime platform settings keys.
    public static final String COMMISSION_SETTING_KEY = "commission_percent";
}
